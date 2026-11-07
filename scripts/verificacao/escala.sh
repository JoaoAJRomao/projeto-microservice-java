#!/usr/bin/env bash
# =============================================================================
#  Verificação da escala horizontal contra o docker compose de verdade (ADR-0005).
#
#  Uso:  bash scripts/verificacao/escala.sh <cenario>
#        bash scripts/verificacao/escala.sh --lista
#
#  Cada cenário sobe o que precisa, decide pelo código de saída (0 = passou,
#  1 = falhou) e devolve o ambiente ao padrão (2 réplicas de cada serviço).
#  Cenário desconhecido sai com código 2. Requer docker, curl e python3.
#  "migracao-concorrente" usa um projeto compose separado: os volumes do
#  ambiente principal não são apagados.
# =============================================================================
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$RAIZ"

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
EUREKA="${EUREKA_URL_HOST:-http://127.0.0.1:8761}"
PROJETO="cadastro-notificacao"

dc() { docker compose "$@"; }
agora() { python3 -c 'import time; print(f"{time.time():.3f}")'; }
passou() { printf '\033[1;32mPASSOU\033[0m %s\n' "$1"; }
falha() { printf '\033[1;31mFALHOU\033[0m %s\n' "$1" >&2; exit 1; }
info() { printf '  %s\n' "$1"; }
ate() { python3 -c "import sys; print(f'{float(sys.argv[2]) - float(sys.argv[1]):.1f}')" "$1" "$2"; }
menor_ou_igual() { python3 -c "import sys; sys.exit(0 if float(sys.argv[1]) <= float(sys.argv[2]) else 1)" "$1" "$2"; }

# --------------------------------------------------------------- ambiente ---
containers() { dc ps -q "$1" 2>/dev/null; }
hostname_de() { docker inspect -f '{{.Config.Hostname}}' "$1"; }
ip_edge_de() { docker inspect -f "{{(index .NetworkSettings.Networks \"${PROJETO}_edge\").IPAddress}}" "$1"; }
reinicios_de() { docker inspect -f '{{.RestartCount}}' "$1"; }

# saudaveis <servico>: quantas réplicas estão "healthy"
saudaveis() {
  local n=0 c
  for c in $(containers "$1"); do
    [ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$c")" = "healthy" ] && n=$((n + 1))
  done
  echo "$n"
}

# esperar_saudaveis <servico> <quantidade> [timeout]
esperar_saudaveis() {
  local servico=$1 qtd=$2 limite=${3:-240} i total
  for i in $(seq 1 "$limite"); do
    total=$(containers "$servico" | wc -l | tr -d ' ')
    [ "$total" -eq "$qtd" ] && [ "$(saudaveis "$servico")" -eq "$qtd" ] && return 0
    sleep 1
  done
  falha "$servico: esperava $qtd réplicas healthy, há $(saudaveis "$servico") de $(containers "$servico" | wc -l | tr -d ' ')"
}

# registro <APP>: uma linha "instanceId ip status" por instância no Eureka
registro() {
  curl -fsS -H 'Accept: application/json' "$EUREKA/eureka/apps/$1" 2>/dev/null | python3 -c '
import json, sys
try:
    app = json.load(sys.stdin)["application"]
except Exception:
    sys.exit(0)
for i in app["instance"]:
    print(i["instanceId"], i["ipAddr"], i["status"])
' || true
}

registradas_up() { registro "$1" | awk '$3 == "UP"' | wc -l | tr -d ' '; }

esperar_registradas() {
  local app=$1 qtd=$2 limite=${3:-60} i
  for i in $(seq 1 "$limite"); do
    [ "$(registradas_up "$app")" -eq "$qtd" ] && [ "$(registro "$app" | wc -l | tr -d ' ')" -eq "$qtd" ] && return 0
    sleep 1
  done
  falha "$app: esperava $qtd instâncias UP no Eureka, há: $(registro "$app" | tr '\n' ';')"
}

# Ambiente padrão: tudo no ar, 2 réplicas de cada serviço registradas e vistas pelo gateway.
padrao() {
  dc up -d --scale ms-user=2 --scale ms-email=2 >/dev/null 2>&1 || dc up -d --scale ms-user=2 --scale ms-email=2
  esperar_saudaveis discovery-server 1
  esperar_saudaveis api-gateway 1
  esperar_saudaveis ms-user 2
  esperar_saudaveis ms-email 2
  esperar_registradas MS-USER 2
  esperar_registradas MS-EMAIL 2
  sleep 12   # busca do registro (5 s) + cache do LoadBalancer (5 s) no gateway
}

# --------------------------------------------------------------- HTTP ------
cadastrar() { # cadastrar <correlationId> <email> -> imprime "status corpo"
  curl -sS -o /tmp/escala-corpo -w '%{http_code}' -X POST "$GATEWAY/user" \
    -H 'Content-Type: application/json' -H "X-Correlation-ID: $1" \
    -d "{\"name\":\"Pessoa Escala\",\"email\":\"$2\"}"
}
json() { python3 -c "import json,sys; d=json.load(open('$1')); print(eval(sys.argv[1], {}, {'d': d}))" "$2"; }

# Quantas linhas de log com o padrão cada container de um serviço tem: "container quantidade"
contagem_por_container() {
  local servico=$1 padrao=$2 c
  for c in $(containers "$servico"); do
    echo "$(hostname_de "$c") $(docker logs "$c" 2>&1 | grep -c -- "$padrao" || true)"
  done
}

psql_email() { dc exec -T email-db psql -U "${EMAIL_DB_USER:-ms_email}" -d ms_email -tAc "$1"; }
psql_user() { dc exec -T user-db psql -U "${USER_DB_USER:-ms_user}" -d ms_user -tAc "$1"; }

# ============================================================== cenários ===

cenario_cadastro_pelo_gateway() {
  padrao
  local email="Maria.Gateway.$(date +%s)@Exemplo.COM" status
  status=$(cadastrar "gateway-$(date +%s)" "$email")
  [ "$status" = "201" ] || falha "POST :8080/user respondeu $status: $(cat /tmp/escala-corpo)"
  [ -n "$(json /tmp/escala-corpo "d['userId']")" ] || falha "corpo sem userId"
  [ "$(json /tmp/escala-corpo "d['name']")" = "Pessoa Escala" ] || falha "name diferente"
  [ "$(json /tmp/escala-corpo "d['email']")" = "$(echo "$email" | tr '[:upper:]' '[:lower:]')" ] \
    || falha "e-mail não normalizado: $(cat /tmp/escala-corpo)"
  passou "POST :8080/user -> 201 com o corpo do ms-user e e-mail em minúsculas"
}

cenario_portas() {
  padrao
  local p
  for p in 8081 8082; do
    if curl -sS -o /dev/null --max-time 3 "http://localhost:$p/health" 2>/dev/null; then
      falha "localhost:$p respondeu; deveria recusar conexão"
    fi
  done
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/health")" = "200" ] || falha ":8080/health não respondeu 200"
  for c in $(containers ms-user) $(containers ms-email); do
    [ -z "$(docker port "$c")" ] || falha "réplica $(hostname_de "$c") publica porta no host: $(docker port "$c")"
  done
  passou "8081 e 8082 recusam conexão; 8080/health -> 200; réplicas sem porta no host"
}

cenario_health_gateway() {
  padrao
  curl -fsS "$GATEWAY/health" > /tmp/escala-health || falha ":8080/health não respondeu 200"
  [ "$(json /tmp/escala-health "d['status']")" = "UP" ] || falha "status diferente de UP"
  local servicos
  servicos=$(json /tmp/escala-health "sorted(d['components']['discoveryComposite']['components']['discoveryClient']['details']['services'])")
  [ "$servicos" = "['ms-email', 'ms-user']" ] || falha "discoveryComposite lista $servicos"
  passou ":8080/health UP com ms-user e ms-email em components.discoveryComposite"
}

cenario_correlation_ponta_a_ponta() {
  padrao
  local headers id userId atendeu
  headers=$(curl -sS -D - -o /tmp/escala-corpo -X POST "$GATEWAY/user" -H 'Content-Type: application/json' \
    -d "{\"name\":\"Sem Header\",\"email\":\"sem.header.$(date +%s)@exemplo.com\"}")
  id=$(echo "$headers" | tr -d '\r' | awk -F': ' 'tolower($1) == "x-correlation-id" {print $2}')
  [[ "$id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || falha "gateway não gerou UUID: '$id'"
  userId=$(json /tmp/escala-corpo "d['userId']")
  sleep 1
  atendeu=$(contagem_por_container ms-user "$id" | awk '$2 > 0' | wc -l | tr -d ' ')
  [ "$atendeu" = "1" ] || falha "o ID $id aparece em $atendeu réplicas de ms-user: $(contagem_por_container ms-user "$id" | tr '\n' ';')"
  local i cid=""
  for i in $(seq 1 20); do
    cid=$(psql_email "SELECT correlation_id FROM emails WHERE user_id = '$userId'")
    [ -n "$cid" ] && break
    sleep 0.5
  done
  [ "$cid" = "$id" ] || falha "emails.correlation_id = '$cid', esperado '$id'"
  passou "ID gerado pelo gateway ($id) no log da réplica que atendeu e em emails.correlation_id"
}

cenario_registro() {
  padrao
  local c app host ip linha
  for servico in ms-user ms-email; do
    app=$(echo "$servico" | tr '[:lower:]' '[:upper:]')
    for c in $(containers "$servico"); do
      host=$(hostname_de "$c"); ip=$(ip_edge_de "$c")
      linha=$(registro "$app" | awk -v id="$servico:$host" '$1 == id')
      [ -n "$linha" ] || falha "$servico:$host não está no registro"
      [ "$(echo "$linha" | awk '{print $2}')" = "$ip" ] || falha "$servico:$host registrou $(echo "$linha" | awk '{print $2}'), IP na rede edge é $ip"
      [ "$(echo "$linha" | awk '{print $3}')" = "UP" ] || falha "$servico:$host não está UP"
    done
  done
  info "instâncias atuais com instanceId <app>:<hostname> e IP da rede edge"

  local antigas nova="" host_nova subiu="" registrou=""
  antigas=$(containers ms-user)
  dc up -d --no-recreate --scale ms-user=3 ms-user >/dev/null 2>&1
  for c in $(containers ms-user); do echo "$antigas" | grep -cx "$c" >/dev/null || nova=$c; done
  [ -n "$nova" ] || falha "nenhuma réplica nova criada"
  host_nova=$(hostname_de "$nova")
  for i in $(seq 1 480); do
    if [ -z "$registrou" ] && [ -n "$(registro MS-USER | awk -v id="ms-user:$host_nova" '$1 == id && $3 == "UP"')" ]; then
      registrou=$(agora)
    fi
    docker logs "$nova" > /tmp/escala-nova.log 2>&1
    [ -n "$registrou" ] && grep -c 'Started UserApplication' /tmp/escala-nova.log >/dev/null && break
    sleep 0.25
  done
  [ -n "$registrou" ] || falha "réplica nova ms-user:$host_nova não apareceu UP no registro"
  # "Terminou de subir" = linha "Started UserApplication" (relógio do container, o mesmo do host no Docker Desktop).
  subiu=$(grep -m1 'Started UserApplication' /tmp/escala-nova.log | python3 -c '
import json, sys, datetime
t = json.loads(sys.stdin.readline())["@timestamp"].replace("Z", "+00:00")
print(datetime.datetime.fromisoformat(t[:26] + t[-6:]).timestamp())')
  [ -n "$subiu" ] || falha "réplica nova não registrou a linha Started UserApplication"
  local atraso; atraso=$(ate "$subiu" "$registrou")
  info "réplica nova ms-user:$host_nova UP no registro ${atraso} s depois de subir"
  menor_ou_igual "$atraso" 15 || falha "réplica nova levou ${atraso} s (> 15 s) para aparecer"
  [ "$(registro MS-USER | awk -v id="ms-user:$host_nova" '$1 == id {print $2}')" = "$(ip_edge_de "$nova")" ] || falha "IP da réplica nova não é o da rede edge"
  padrao
  passou "réplicas registradas UP com <app>:<hostname> e IP do container; réplica nova em até 15 s"
}

cenario_eureka_app_desconhecida() {
  padrao
  local s; s=$(curl -s -o /dev/null -w '%{http_code}' "$EUREKA/eureka/apps/NAO-EXISTE")
  [ "$s" = "404" ] || falha "/eureka/apps/NAO-EXISTE respondeu $s"
  passou "/eureka/apps/NAO-EXISTE -> 404"
}

cenario_descadastro_sigterm() {
  padrao
  local alvo host inicio saiu=""
  alvo=$(containers ms-user | head -1); host=$(hostname_de "$alvo")
  inicio=$(agora)
  docker stop "$alvo" >/dev/null &
  for i in $(seq 1 40); do
    if ! registro MS-USER | awk '{print $1}' | grep -cx "ms-user:$host" >/dev/null; then saiu=$(agora); break; fi
    sleep 0.25
  done
  wait
  [ -n "$saiu" ] || falha "ms-user:$host continua no registro 10 s depois do SIGTERM"
  local atraso; atraso=$(ate "$inicio" "$saiu")
  info "ms-user:$host saiu do registro ${atraso} s depois do SIGTERM"
  docker start "$alvo" >/dev/null
  menor_ou_igual "$atraso" 5 || falha "saída do registro levou ${atraso} s (> 5 s)"
  padrao
  passou "réplica parada com SIGTERM sai do registro em até 5 s"
}

cenario_despejo_sigkill() {
  padrao
  local alvo host inicio saiu=""
  alvo=$(containers ms-user | head -1); host=$(hostname_de "$alvo")
  docker update --restart=no "$alvo" >/dev/null
  inicio=$(agora)
  docker kill "$alvo" >/dev/null
  for i in $(seq 1 90); do
    if ! registro MS-USER | awk '{print $1}' | grep -cx "ms-user:$host" >/dev/null; then saiu=$(agora); break; fi
    sleep 0.5
  done
  docker update --restart=unless-stopped "$alvo" >/dev/null
  docker start "$alvo" >/dev/null
  [ -n "$saiu" ] || falha "ms-user:$host continua no registro 45 s depois do SIGKILL"
  local atraso; atraso=$(ate "$inicio" "$saiu")
  info "ms-user:$host removida do registro ${atraso} s depois do SIGKILL"
  menor_ou_igual "$atraso" 30 || falha "remoção levou ${atraso} s (> 30 s)"
  padrao
  passou "réplica morta com SIGKILL sai do registro em até 30 s"
}

cenario_discovery_fora() {
  padrao
  local espera="${ESPERA_DISCOVERY_FORA:-300}" status
  dc stop discovery-server >/dev/null 2>&1
  info "discovery-server parado; esperando ${espera} s"
  sleep "$espera"
  status=$(cadastrar "discovery-fora-$(date +%s)" "discovery.fora.$(date +%s)@exemplo.com")
  dc start discovery-server >/dev/null 2>&1
  [ "$status" = "201" ] || falha "com o discovery-server parado há ${espera} s, POST respondeu $status: $(cat /tmp/escala-corpo)"
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/emails?page=0&size=1")" = "200" ] || true
  padrao
  passou "com o discovery-server parado há ${espera} s, POST :8080/user -> 201"
}

cenario_discovery_volta() {
  padrao
  local voltou="" registrou="" c
  dc stop discovery-server >/dev/null 2>&1
  sleep 20
  dc start discovery-server >/dev/null 2>&1
  for i in $(seq 1 240); do
    if curl -fsS -o /dev/null "$EUREKA/eureka/apps" 2>/dev/null; then voltou=$(agora); break; fi
    sleep 0.5
  done
  [ -n "$voltou" ] || falha "discovery-server não voltou a responder"
  for i in $(seq 1 90); do
    if [ "$(registradas_up MS-USER)" -eq 2 ] && [ "$(registradas_up MS-EMAIL)" -eq 2 ]; then registrou=$(agora); break; fi
    sleep 0.5
  done
  [ -n "$registrou" ] || falha "réplicas não se registraram de novo: $(registro MS-USER | tr '\n' ';') $(registro MS-EMAIL | tr '\n' ';')"
  local atraso; atraso=$(ate "$voltou" "$registrou")
  info "4 réplicas registradas de novo ${atraso} s depois de o discovery-server responder"
  menor_ou_igual "$atraso" 30 || falha "re-registro levou ${atraso} s (> 30 s)"
  for c in $(containers ms-user) $(containers ms-email); do
    [ "$(reinicios_de "$c")" = "0" ] || falha "$(hostname_de "$c") reiniciou ($(reinicios_de "$c") vezes)"
  done
  padrao
  passou "com o discovery-server de volta, todas as réplicas se registram em até 30 s, sem reinício"
}

cenario_rabbit_fora() {
  padrao
  local status
  dc stop rabbitmq >/dev/null 2>&1
  sleep 20
  local up_user up_email
  up_user=$(registradas_up MS-USER); up_email=$(registradas_up MS-EMAIL)
  status=$(cadastrar "rabbit-fora-$(date +%s)" "rabbit.fora.$(date +%s)@exemplo.com")
  dc start rabbitmq >/dev/null 2>&1
  [ "$up_user" = "2" ] && [ "$up_email" = "2" ] || falha "com o RabbitMQ parado: MS-USER UP=$up_user, MS-EMAIL UP=$up_email"
  [ "$status" = "201" ] || falha "com o RabbitMQ parado, POST respondeu $status"
  esperar_saudaveis rabbitmq 1
  padrao
  passou "com o RabbitMQ parado, as réplicas seguem UP no registro e POST -> 201"
}

cenario_replicas_padrao() {
  [ ! -f .env ] || falha "existe um .env na raiz; este cenário confere os padrões sem ele"
  env -u MS_USER_REPLICAS -u MS_EMAIL_REPLICAS docker compose up -d >/dev/null 2>&1
  esperar_saudaveis ms-user 2
  esperar_saudaveis ms-email 2
  passou "sem .env: 2 réplicas healthy de ms-user e 2 de ms-email"
}

cenario_replicas_variavel() {
  MS_USER_REPLICAS=3 docker compose up -d >/dev/null 2>&1
  esperar_saudaveis ms-user 3
  esperar_saudaveis ms-email 2
  padrao
  passou "MS_USER_REPLICAS=3: 3 réplicas healthy de ms-user"
}

cenario_scale_4() {
  padrao
  dc up -d --scale ms-user=4 > /tmp/escala-up.log 2>&1 || falha "docker compose up --scale ms-user=4 falhou: $(cat /tmp/escala-up.log)"
  ! grep -qi 'port is already allocated' /tmp/escala-up.log || falha "erro de porta ao escalar"
  esperar_saudaveis ms-user 4
  padrao
  passou "--scale ms-user=4: 4 réplicas healthy, sem erro de porta"
}

cenario_round_robin() {
  padrao
  local prefixo="rr-$(date +%s)" i
  for i in $(seq -w 1 20); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' -H "X-Correlation-ID: $prefixo-$i" "$GATEWAY/user?page=0&size=1")" = "200" ] \
      || falha "GET $i não respondeu 200"
  done
  sleep 1
  local contagem; contagem=$(contagem_por_container ms-user "$prefixo-")
  info "atendidas por réplica: $(echo "$contagem" | tr '\n' ';')"
  echo "$contagem" | awk '{ if ($2 < 9 || $2 > 11) exit 1; total += $2 } END { if (total != 20) exit 1 }' \
    || falha "distribuição fora de 9..11 por réplica: $(echo "$contagem" | tr '\n' ';')"
  passou "20 GETs: entre 9 e 11 por réplica de ms-user"
}

cenario_replica_nova() {
  padrao
  local antigas nova="" saudavel="" atendeu="" prefixo="nova-$(date +%s)" n=0 c
  antigas=$(containers ms-user)
  dc up -d --no-recreate --scale ms-user=3 ms-user >/dev/null 2>&1
  for c in $(containers ms-user); do echo "$antigas" | grep -cx "$c" >/dev/null || nova=$c; done
  [ -n "$nova" ] || falha "nenhuma réplica nova criada"
  for i in $(seq 1 240); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$nova")" = "healthy" ] && { saudavel=$(agora); break; }
    sleep 0.5
  done
  [ -n "$saudavel" ] || falha "réplica nova não ficou healthy"
  while [ -z "$atendeu" ]; do
    n=$((n + 1))
    curl -s -o /dev/null -H "X-Correlation-ID: $prefixo-$n" "$GATEWAY/user?page=0&size=1"
    if docker logs "$nova" 2>&1 | grep -c -- "$prefixo-" >/dev/null; then atendeu=$(agora); fi
    menor_ou_igual "$(ate "$saudavel" "$(agora)")" 45 || break
    sleep 0.3
  done
  [ -n "$atendeu" ] || falha "réplica nova $(hostname_de "$nova") não recebeu requisição em 45 s"
  local atraso; atraso=$(ate "$saudavel" "$atendeu")
  info "réplica nova $(hostname_de "$nova") atendeu ${atraso} s depois de ficar healthy"
  menor_ou_igual "$atraso" 30 || falha "réplica nova levou ${atraso} s (> 30 s) para receber tráfego"
  padrao
  passou "réplica nova recebe requisições do gateway em até 30 s depois de ficar healthy"
}

cenario_consumidores() {
  padrao
  local n
  for i in $(seq 1 30); do
    n=$(dc exec -T rabbitmq rabbitmqctl list_queues name consumers -q 2>/dev/null | awk '$1 == "email.boas-vindas" {print $2}')
    [ "$n" = "4" ] && break
    sleep 1
  done
  [ "$n" = "4" ] || falha "email.boas-vindas tem $n consumidores, esperado 4"
  passou "email.boas-vindas com 4 consumidores (2 réplicas x 2)"
}

cenario_cem_cadastros() {
  padrao
  local prefixo="cem-$(date +%s)" i status antes depois
  antes=$(psql_email "SELECT count(*) FROM emails")
  for i in $(seq -w 1 100); do
    status=$(cadastrar "$prefixo-$i" "$prefixo-$i@exemplo.com")
    [ "$status" = "201" ] || falha "cadastro $i respondeu $status"
  done
  for i in $(seq 1 120); do
    depois=$(psql_email "SELECT count(*) FROM emails")
    [ $((depois - antes)) -ge 100 ] && break
    sleep 0.5
  done
  sleep 2
  depois=$(psql_email "SELECT count(*) FROM emails")
  [ $((depois - antes)) -eq 100 ] || falha "emails ganhou $((depois - antes)) linhas, esperado 100"
  local distintos
  distintos=$(psql_email "SELECT count(DISTINCT user_id) FROM emails WHERE correlation_id LIKE '$prefixo-%'")
  [ "$distintos" = "100" ] || falha "$distintos userIds distintos entre as linhas novas, esperado 100"
  local contagem; contagem=$(contagem_por_container ms-email "E-mail processado.*$prefixo-\|$prefixo-.*E-mail processado")
  info "processados por réplica de ms-email: $(echo "$contagem" | tr '\n' ';')"
  echo "$contagem" | awk '{ if ($2 < 30 || $2 > 70) exit 1; total += $2 } END { if (total != 100) exit 1 }' \
    || falha "distribuição fora de 30..70 por réplica: $(echo "$contagem" | tr '\n' ';')"
  passou "100 cadastros -> 100 linhas novas em emails, uma por userId, 30..70 por réplica"
}

cenario_scale_5() {
  padrao
  dc up -d --scale ms-user=5 --scale ms-email=5 > /tmp/escala-up.log 2>&1 || falha "scale 5 falhou: $(cat /tmp/escala-up.log)"
  esperar_saudaveis ms-user 5 300
  esperar_saudaveis ms-email 5 300
  local cu ce
  cu=$(psql_user "SELECT count(*) FROM pg_stat_activity WHERE datname = 'ms_user'")
  ce=$(psql_email "SELECT count(*) FROM pg_stat_activity WHERE datname = 'ms_email'")
  info "conexões abertas: user-db=$cu email-db=$ce"
  [ "$cu" -le 60 ] && [ "$ce" -le 60 ] || falha "conexões acima de 60: user-db=$cu email-db=$ce"
  padrao
  passou "10 réplicas healthy; no máximo 60 conexões por Postgres"
}

cenario_kill_com_gets() {
  padrao
  local alvo i s falhas=0
  alvo=$(containers ms-user | head -1)
  docker update --restart=no "$alvo" >/dev/null
  docker kill "$alvo" >/dev/null
  for i in $(seq 1 20); do
    s=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/user?page=0&size=1")
    [ "$s" = "200" ] || { falhas=$((falhas + 1)); info "GET $i -> $s"; }
  done
  docker update --restart=unless-stopped "$alvo" >/dev/null
  docker start "$alvo" >/dev/null
  [ "$falhas" -eq 0 ] || falha "$falhas de 20 GETs não responderam 200 logo após o docker kill"
  padrao
  passou "docker kill em uma réplica e 20 GETs em seguida -> 20 x 200"
}

cenario_ms_user_parado() {
  padrao
  dc stop ms-user >/dev/null 2>&1
  sleep 15
  local status detalhe emails
  status=$(cadastrar "parado-$(date +%s)" "parado.$(date +%s)@exemplo.com")
  detalhe=$(json /tmp/escala-corpo "d.get('detail', '')" 2>/dev/null || true)
  emails=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/emails?page=0&size=1")
  dc start ms-user >/dev/null 2>&1
  [ "$status" = "503" ] || falha "com ms-user parado, POST respondeu $status"
  [[ "$detalhe" == *ms-user* ]] || falha "detail não cita ms-user: '$detalhe'"
  [ "$emails" = "200" ] || falha "GET /emails respondeu $emails com ms-user parado"
  padrao
  passou "ms-user parado: POST -> 503 citando ms-user; GET /emails -> 200"
}

cenario_migracao_concorrente() {
  local projeto="cadastro-notificacao-migracao" c
  local -a portas=(GATEWAY_PORT=18080 DISCOVERY_UI_PORT=18761 USER_DB_PORT=15433 EMAIL_DB_PORT=15434
    RABBITMQ_PORT=15672 RABBITMQ_UI_PORT=25672 MAILPIT_UI_PORT=18025 MAILPIT_SMTP_PORT=11025)
  mig() { env "${portas[@]}" docker compose -p "$projeto" "$@"; }
  info "pausando o ambiente principal (os volumes ficam) e subindo $projeto com bancos vazios"
  dc stop >/dev/null 2>&1
  mig down -v >/dev/null 2>&1 || true
  mig up -d >/dev/null 2>&1 || { mig down -v >/dev/null 2>&1; dc start >/dev/null 2>&1; falha "não subiu $projeto"; }
  local ok=1 motivo=""
  for i in $(seq 1 240); do
    local saud=0 total=0
    for c in $(mig ps -q ms-user ms-email); do
      total=$((total + 1))
      [ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$c")" = "healthy" ] && saud=$((saud + 1))
    done
    [ "$total" -eq 4 ] && [ "$saud" -eq 4 ] && { ok=0; break; }
    sleep 1
  done
  [ "$ok" -eq 0 ] || motivo="réplicas não ficaram healthy"
  if [ -z "$motivo" ]; then
    for c in $(mig ps -q ms-user ms-email); do
      [ "$(reinicios_de "$c")" = "0" ] || motivo="$motivo $(hostname_de "$c") reiniciou;"
    done
    local vu ve
    vu=$(mig exec -T user-db psql -U ms_user -d ms_user -tAc "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")
    ve=$(mig exec -T email-db psql -U ms_email -d ms_email -tAc "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")
    info "flyway_schema_history versão 1: user-db=$vu email-db=$ve"
    [ "$vu" = "1" ] && [ "$ve" = "1" ] || motivo="$motivo versão 1 aparece user-db=$vu email-db=$ve vezes;"
  fi
  mig down -v >/dev/null 2>&1
  dc start >/dev/null 2>&1
  [ -z "$motivo" ] || falha "$motivo"
  padrao
  passou "2+2 réplicas subindo juntas com bancos vazios: healthy, sem reinício, V1 uma vez em cada banco"
}

cenario_demo() {
  padrao
  local cid="demo-escala-$(date +%s)"
  CID="$cid" bash scripts/demo.sh > /tmp/escala-demo.log 2>&1 || falha "demo.sh saiu com erro: $(tail -5 /tmp/escala-demo.log)"
  [ "$(dc logs --no-log-prefix ms-user 2>/dev/null | grep -c -- "$cid")" -gt 0 ] || falha "correlationId $cid não está nos logs do ms-user"
  [ "$(dc logs --no-log-prefix ms-email 2>/dev/null | grep -c -- "$cid")" -gt 0 ] || falha "correlationId $cid não está nos logs do ms-email"
  grep -q -- "$cid" /tmp/escala-demo.log || falha "demo.sh não mostrou o correlationId"
  passou "demo.sh termina com 0 pelo gateway e o mesmo correlationId aparece em ms-user e ms-email"
}

# Confere o alvo do k6 sem gerar carga; a execução fica com o grupo (K6_EXECUTAR=1 roda 10 s).
cenario_k6() {
  local resultado="docs/performance/resultados/resultado-k6.md" base_user base_email
  base_user=$(dc --profile loadtest config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["k6"]["environment"]["BASE_URL_USER"])')
  base_email=$(dc --profile loadtest config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["k6"]["environment"]["BASE_URL_EMAIL"])')
  [ "$base_user" = "http://api-gateway:8080" ] && [ "$base_email" = "http://api-gateway:8080" ] \
    || falha "k6 usa BASE_URL_USER=$base_user BASE_URL_EMAIL=$base_email"
  if [ "${K6_EXECUTAR:-0}" = "1" ]; then
    padrao
    dc --profile loadtest run --rm -e DURATION="${K6_DURATION:-10s}" k6 > /tmp/escala-k6.log 2>&1 || info "k6 terminou com thresholds violados"
  fi
  [ -f "$resultado" ] || falha "$resultado não existe: rode o k6 pelo gateway (K6_EXECUTAR=1)"
  grep -c 'POST /user' "$resultado" >/dev/null || falha "$resultado sem a tabela de POST /user"
  passou "k6 usa http://api-gateway:8080 nas duas URLs e há um resultado-k6.md gravado"
}

# ================================================================= main ===
CENARIOS=(cadastro-pelo-gateway portas health-gateway correlation-ponta-a-ponta registro
  eureka-app-desconhecida descadastro-sigterm despejo-sigkill discovery-fora discovery-volta
  rabbit-fora replicas-padrao replicas-variavel scale-4 round-robin replica-nova consumidores
  cem-cadastros scale-5 kill-com-gets ms-user-parado migracao-concorrente demo k6)

if [ "${1:-}" = "--lista" ]; then printf '%s\n' "${CENARIOS[@]}"; exit 0; fi
alvo="${1:-}"
for c in "${CENARIOS[@]}"; do
  if [ "$c" = "$alvo" ]; then
    printf '\033[1;36m==> %s\033[0m\n' "$alvo"
    "cenario_${alvo//-/_}"
    exit 0
  fi
done
echo "cenário desconhecido: '${alvo}'. Use --lista para ver os cenários." >&2
exit 2
