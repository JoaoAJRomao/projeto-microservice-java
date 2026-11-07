#!/usr/bin/env bash
# =============================================================================
#  Roteiro da demonstração ao vivo (Linux, macOS ou Git Bash no Windows).
#  Pré-requisito: "docker compose up --build -d" já executado.
#  Tudo passa pelo api-gateway (:8080), que distribui entre as réplicas (ADR-0005).
#
#  1. Espera o gateway e os dois serviços (pelo gateway) ficarem prontos
#  2. Cadastra um usuário enviando um Correlation ID conhecido
#  3. Mostra o e-mail registrado pelo ms-email com o MESMO Correlation ID
#  4. Filtra os logs JSON dos dois serviços (todas as réplicas) por esse ID
#  5. Mostra a regra de negócio (e-mail duplicado -> 409)
# =============================================================================
set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
CID="${CID:-demo-$(date +%s)}"
EMAIL="aluno.${CID}@example.com"

titulo() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }

titulo "1. Aguardando o api-gateway e as réplicas de ms-user e ms-email"
for caminho in /health/readiness "/user?page=0&size=1" "/emails?page=0&size=1"; do
  for i in $(seq 1 60); do
    if curl -fsS "$GATEWAY_URL$caminho" >/dev/null 2>&1; then echo "  $GATEWAY_URL$caminho  OK"; break; fi
    [ "$i" -eq 60 ] && { echo "  $GATEWAY_URL$caminho não respondeu em 120 s"; exit 1; }
    sleep 2
  done
done
curl -fsS "$GATEWAY_URL/health"; echo

titulo "2. POST /user  (X-Correlation-ID: $CID)"
RESP=$(curl -sS -i -X POST "$GATEWAY_URL/user" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: $CID" \
  -d "{\"name\":\"Aluno Demo\",\"email\":\"$EMAIL\"}")
echo "$RESP"
USER_ID=$(echo "$RESP" | grep -o '"userId":"[^"]*"' | cut -d'"' -f4)

titulo "3. GET /emails/user/$USER_ID  (aguardando o consumo assíncrono)"
for i in $(seq 1 20); do
  EMAILS=$(curl -fsS "$GATEWAY_URL/emails/user/$USER_ID")
  if [ "$EMAILS" != "[]" ]; then echo "$EMAILS"; break; fi
  [ "$i" -eq 20 ] && echo "  Nenhum e-mail registrado em 10 s — veja: docker compose logs ms-email"
  sleep 0.5
done

titulo "4. Logs do gateway e dos DOIS serviços filtrados pelo Correlation ID (o prefixo mostra a réplica)"
docker compose logs api-gateway ms-user ms-email 2>/dev/null | grep "$CID" || echo "(rode este script na pasta do docker-compose.yml)"

titulo "5. Regra de negócio: mesmo e-mail de novo -> 409 Conflict"
curl -sS -i -X POST "$GATEWAY_URL/user" -H "Content-Type: application/json" \
  -d "{\"name\":\"Aluno Demo\",\"email\":\"$EMAIL\"}" | sed -n '1p;/^{/p'

titulo "Pronto. Caixa de entrada: http://localhost:8025  |  RabbitMQ: http://localhost:15672 (app / app_dev)  |  Eureka: http://localhost:8761"
