# Cadastro de Usuários + Notificação por E-mail — Microsserviços

Dois microsserviços Spring Boot que se comunicam de forma assíncrona, atrás de um API Gateway:

- **ms-user** — cadastra usuários (e-mail único) e publica um pedido de e-mail de boas-vindas no RabbitMQ;
- **ms-email** — consome os pedidos, envia via SMTP e guarda o histórico de envios;
- **api-gateway** (`:8080`) — porta de entrada única: roteia `/user` e `/emails` para as réplicas, com retry, timeout e circuit breaker;
- **discovery-server** (`:8761`) — registro de serviços (Eureka): cada réplica se registra ao subir e sai ao parar.

O ambiente sobe com **2 réplicas de cada serviço** (configurável). Cada serviço tem seu próprio PostgreSQL, com esquema versionado pelo Flyway. Tudo (gateway, registro, réplicas, bancos, broker e SMTP de testes) sobe com **um comando**.

```
                                 ┌──▶ ms-user (réplica 1..N) ──JDBC──▶ user-db
Cliente ──HTTP :8080──▶ api-gateway                   │
                  (lb://)    │   └──▶ ms-email (réplica 1..N) ──JDBC──▶ email-db
                             │            ▲            └──SMTP──▶ Mailpit
              discovery-server (Eureka)   │
               ▲ registro + heartbeat     └── RabbitMQ ◀──AMQP (X-Correlation-ID)── ms-user
```

Arquitetura completa, SLOs, observabilidade e segurança: **[docs/arquitetura.md](docs/arquitetura.md)**.

---

## Onde está cada entregável

| Entregável exigido | Onde | Critério |
|---|---|---|
| Documento de arquitetura (contexto, problema, SLOs, observabilidade, segurança) | [`docs/arquitetura.md`](docs/arquitetura.md) | Coerência arquitetural (25%) |
| Mínimo de 3 ADRs (contexto, alternativas, decisão, consequências) | [`docs/adr/`](docs/adr/README.md) — 6 ADRs | Qualidade dos ADRs (20%) |
| ADR revisado após o Peer Review | Tabela "Histórico de revisões" de cada ADR + [`docs/peer-review/feedback-recebido.md`](docs/peer-review/feedback-recebido.md) | Qualidade dos ADRs |
| Código de 2 microsserviços com lógica de negócio | [`ms-user/`](ms-user), [`ms-email/`](ms-email) | — |
| `/health` + logging estruturado com Correlation ID | `observability/` em cada serviço + `application.yaml` | Health check + logging (10%) |
| `docker-compose.yml` que sobe tudo | [`docker-compose.yml`](docker-compose.yml) | Ambiente containerizado (25%) |
| p50/p95/p99 + throughput e conclusão sobre SLOs | [`docs/performance/resultado-medicao.md`](docs/performance/resultado-medicao.md) + script [`load-test/carga-cadastro.js`](load-test/carga-cadastro.js) | Resultado de medição (15%) |
| Checklist de Peer Review | [`docs/peer-review/checklist-peer-review.md`](docs/peer-review/checklist-peer-review.md) (versão para imprimir: [`.pdf`](docs/peer-review/checklist-peer-review.pdf)) | Participação no Peer Review (5%) |

---

## Como rodar

**Pré-requisito:** Docker Desktop (Windows/macOS) ou Docker Engine + Compose v2 (Linux), com **pelo menos 6 GB de memória** para o Docker (4 réplicas Java + gateway + registro + bancos e broker ≈ 5 GB no padrão). Nada mais — Java e Maven rodam dentro do build.

```bash
docker compose up --build -d     # build das imagens + sobe tudo em segundo plano
docker compose ps                # aguarde as réplicas ms-user-1/2 e ms-email-1/2 ficarem "healthy" (~1 min)
```

| O quê | Endereço |
|---|---|
| API (gateway) | http://localhost:8080 — `/user` e `/emails`, mesmos caminhos de antes |
| Health check do gateway | http://localhost:8080/health (réplicas registradas em `discoveryComposite`) |
| Registro de serviços (Eureka) | http://localhost:8761 |
| Caixa de entrada (Mailpit) | http://localhost:8025 |
| RabbitMQ (filas, DLQ, consumidores) | http://localhost:15672 — usuário `app`, senha `app_dev` |

`ms-user` e `ms-email` não publicam porta no host: só o gateway os alcança, e é isso que permite subir várias réplicas.

Parar: `docker compose down` (mantém dados) · `docker compose down -v` (apaga os bancos).

Não é preciso criar `.env`: sem ele, valem os padrões de desenvolvimento. Para trocar senhas, portas ou o número de réplicas, copie `.env.example` para `.env`.

### Escalar

```bash
docker compose up -d --scale ms-user=4          # 4 réplicas de ms-user, sem mexer no cliente
docker compose up -d                            # volta ao padrão (MS_USER_REPLICAS / MS_EMAIL_REPLICAS, 2 de cada)
```

A réplica nova se registra no Eureka e passa a receber requisições do gateway em segundos. As mensagens do RabbitMQ se distribuem sozinhas entre os consumidores de todas as réplicas do `ms-email`.

### Testar rapidamente

```bash
curl -i -X POST http://localhost:8080/user \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: apresentacao-0001" \
  -d '{"name":"Maria Silva","email":"maria.silva@example.com"}'

curl http://localhost:8080/emails                       # o e-mail aparece com o mesmo correlationId
docker compose logs api-gateway ms-user ms-email | grep apresentacao-0001   # o prefixo mostra a réplica
```

No Windows (PowerShell) use `curl.exe` no lugar de `curl`, ou abra [`scripts/demo.http`](scripts/demo.http) no IntelliJ / VS Code (REST Client) e clique em cada requisição.

---

## Roteiro da apresentação ao vivo (~10 min)

| Tempo | O que mostrar | Comando / tela |
|---|---|---|
| 0:00 | Problema e arquitetura | Diagramas de `docs/arquitetura.md` (§3) |
| 1:30 | Decisões | Tabela de ADRs e um ADR completo (sugestão: ADR-0001) |
| 3:00 | **Subir o ambiente** | `docker compose up --build -d` → `docker compose ps` até `healthy` (2 réplicas de cada serviço) |
| 4:00 | Gateway e registro | http://localhost:8080/health e o painel do Eureka em http://localhost:8761 |
| 4:30 | **Escala horizontal** | `docker compose up -d --scale ms-user=3` → réplica nova aparece no Eureka → `GET /user` repetido alterna a réplica nos logs |
| 5:00 | **Fluxo ponta a ponta + Correlation ID** | `./scripts/demo.sh` (ou `scripts/demo.http`) → e-mail no Mailpit → mesmo ID nos logs do gateway e dos dois serviços |
| 6:30 | Resiliência | `docker compose stop ms-email` → cadastrar → mensagem esperando na fila (UI do RabbitMQ) → `docker compose start ms-email` → mensagem consumida; `docker compose stop ms-user` → `POST /user` vira `503` em Problem Details |
| 8:00 | Medição e SLOs | Tabela de `docs/performance/resultado-medicao.md` e conclusão |
| 9:00 | Trade-offs reconhecidos | `docs/arquitetura.md` §9 |

> O `docker compose up --build` na primeira vez baixa imagens e dependências Maven (vários minutos). **Antes da apresentação**, rode `docker compose pull` e `docker compose build` na máquina que vai apresentar — no dia, o build usa o cache e leva segundos.

---

## Teste de carga (k6)

```bash
docker compose down -v && docker compose up --build -d      # bancos zerados
docker compose --profile loadtest run --rm k6                # 50 req/s por endpoint, 2 min
docker compose --profile loadtest run --rm -e RATE=100 -e DURATION=3m k6
```

O k6 fala com o `api-gateway` (`http://api-gateway:8080`), então a medição inclui o salto pela borda e o balanceamento entre as réplicas. O resultado aparece no terminal e é salvo em `docs/performance/resultados/resultado-k6.md` com p50/p95/p99, throughput e o veredito de cada SLO. Copie a tabela para [`docs/performance/resultado-medicao.md`](docs/performance/resultado-medicao.md) e escreva a conclusão.

---

## Rodar pela IDE (opcional)

Suba só a infraestrutura e rode as aplicações no IntelliJ normalmente — os padrões do `application.yaml` já apontam para `localhost`:

```bash
docker compose up -d rabbitmq user-db email-db mailpit discovery-server
```

Os serviços se registram no Eureka em `localhost:8761`. Para rodar um serviço sozinho, sem registro, use `EUREKA_ENABLED=false`. O gateway também roda pela IDE (`api-gateway`, porta 8080). O esquema dos bancos é criado pelo Flyway na primeira subida.

### Testes automatizados

```bash
(cd api-gateway && ./mvnw test)                      # rotas, Correlation ID, retry, timeout, circuit breaker
(cd ms-user && ./mvnw test -Dtest='FlywayMigracaoTest,CadastroConcorrenteTest')   # Postgres real via Testcontainers
bash scripts/verificacao/escala.sh --lista           # cenários contra o docker compose (registro, réplicas, balanceamento...)
bash scripts/verificacao/escala.sh round-robin
```

Para usar o CloudAMQP ou um SMTP real, defina variáveis de ambiente na configuração de execução (nunca no `application.yaml`):
`RABBITMQ_ADDRESSES=amqps://usuario:senha@host/vhost`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH=true`, `MAIL_SMTP_STARTTLS=true`.

---

## Estrutura

```
.
├── docker-compose.yml            ambiente completo (+ perfil "loadtest" com k6)
├── .env.example                  variáveis configuráveis (copiar para .env)
├── api-gateway/                  porta de entrada única: Spring Cloud Gateway + Resilience4j
├── discovery-server/             registro de serviços (Eureka)
├── ms-user/                      microsserviço de cadastro (Dockerfile multi-stage, migrações Flyway)
├── ms-email/                     microsserviço de e-mail (Dockerfile multi-stage, migrações Flyway)
├── load-test/carga-cadastro.js   teste de carga com SLOs como thresholds
├── scripts/                      demo.sh e demo.http para a apresentação; verificacao/ com os cenários de escala
└── docs/
    ├── arquitetura.md            documento de arquitetura consolidado
    ├── adr/                      ADRs 0001–0006 + template
    ├── performance/              metodologia e resultados da medição
    └── peer-review/              checklist (para avaliar outro grupo) e feedback recebido
```

---

## Checklist do grupo antes de entregar

- [ ] **Revogar as credenciais antigas** do CloudAMQP e do servidor SMTP que estavam no `application.yaml` dos repositórios `ms-user-trab` e `ms-email-trab` (elas continuam no histórico do Git).
- [ ] Rodar `docker compose up --build` numa máquina limpa e conferir que tudo fica `healthy`.
- [ ] Rodar o k6, preencher [`resultado-medicao.md`](docs/performance/resultado-medicao.md) (seções 4, 5 e 6) e os dados da máquina.
- [ ] (Opcional) Trocar o exemplo de log em `docs/arquitetura.md` §6.1 por uma linha real de `docker compose logs ms-user`.
- [ ] Preencher integrantes e datas (`docs/arquitetura.md` e ADRs).
- [ ] Após o Peer Review: preencher [`feedback-recebido.md`](docs/peer-review/feedback-recebido.md), revisar ao menos um ADR e registrar no histórico.
- [ ] Imprimir/levar o [checklist de Peer Review](docs/peer-review/checklist-peer-review.md) para avaliar o outro grupo.

---

## Publicar no GitHub (repositório único do grupo)

Este repositório substitui os dois repositórios separados (`ms-user-trab` e `ms-email-trab`):

```bash
git init -b main
git add .
git commit -m "Monorepo: ms-user + ms-email, docker-compose, ADRs, observabilidade e teste de carga"
git remote add origin https://github.com/<usuario-ou-organizacao>/<nome-do-repo>.git
git push -u origin main
```

Adicione os demais integrantes como colaboradores em *Settings → Collaborators*.

---

## Problemas comuns

| Sintoma | Causa provável | Solução |
|---|---|---|
| `port is already allocated` | Containers antigos (`ms-user-db`, `ms-email-db`) ou outro serviço na porta 8080, 8761, 5433, 5434 ou 5672 | `docker ps` e pare-os, ou mude a porta no `.env` (`GATEWAY_PORT`, `DISCOVERY_UI_PORT`...) |
| Conexão recusada nas portas antigas 8081/8082 | Os serviços não publicam mais porta no host | Use o gateway: `http://localhost:8080/user` |
| `ms-user`/`ms-email` ficam `unhealthy` | Banco ou RabbitMQ ainda subindo, ou erro na aplicação | `docker compose logs ms-user` (todas as réplicas) |
| `503` com `"title":"Serviço indisponível"` | Nenhuma réplica do serviço registrada (todas paradas ou ainda subindo), ou circuit breaker aberto por falhas seguidas | `docker compose ps`; painel do Eureka em http://localhost:8761; o circuito fecha sozinho em 10 s |
| Containers reiniciando ou muito lentos com várias réplicas | Pouca memória para o Docker | Docker Desktop → Settings → Resources → Memory ≥ 6 GB, ou menos réplicas (`MS_USER_REPLICAS=1`) |
| `Validate failed: Migrations have failed validation` (Flyway) | Arquivo de migração já aplicado foi editado | Não edite migrações aplicadas; crie uma nova `V2__...sql`. Em desenvolvimento: `docker compose down -v` |
| Build muito lento | Primeiro download das dependências Maven | Normal na primeira vez; as próximas usam cache |
| Mudei o código e nada mudou | Imagem antiga | `docker compose up --build -d` |
| `PRECONDITION_FAILED` no RabbitMQ | Fila criada antes com outros argumentos | `docker compose down -v` (recria o broker) |
