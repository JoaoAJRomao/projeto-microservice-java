# Resultado da Medição de Performance

> ⚠️ **Preencher após rodar o teste.** As seções 4 e 5 ficam vazias até o grupo executar o k6 na própria máquina — os números **não** devem ser estimados. O comando abaixo gera automaticamente a tabela pronta em `docs/performance/resultados/resultado-k6.md`, já com a conclusão SLO a SLO.

## 1. Objetivo

Medir **p50, p95, p99 e throughput** dos endpoints do `ms-user` e a latência do fluxo assíncrono até o `ms-email`, e concluir se o sistema atende aos SLOs definidos em [`docs/arquitetura.md` §5](../arquitetura.md#5-slos-service-level-objectives).

## 2. SLOs avaliados

| SLO | Endpoint / fluxo | Meta |
|---|---|---|
| SLO-1 | `POST /user` e `GET /user` | Erros (5xx/timeout) < 0,5% |
| SLO-2 | `POST /user` | p95 < 200 ms · p99 < 500 ms |
| SLO-3 | `GET /user?page=0&size=20` | p95 < 150 ms · p99 < 300 ms |
| SLO-4 | Cadastro → e-mail registrado no ms-email | ≥ 99% em até 10 s · p95 < 2 s · p99 < 5 s |

## 3. Metodologia

**Ferramenta:** [k6](https://grafana.com/docs/k6/) 2.3, rodando em container na mesma rede Docker dos serviços (`load-test/carga-cadastro.js`).

**Modelo de carga:** executor `constant-arrival-rate` (modelo **aberto**): o k6 dispara N requisições por segundo independentemente de as anteriores terem terminado. Isso evita a *coordinated omission* — num modelo fechado, quando o sistema fica lento o gerador também desacelera e os percentis saem artificialmente bons.

| Cenário | Taxa | Duração | O que faz | Entra no resultado? |
|---|---|---|---|---|
| `aquecimento` | 10 req/s | 20 s | `POST /user` (JIT da JVM, pool de conexões) | Não |
| `cadastro` | `RATE` (padrão 50 req/s) | `DURATION` (padrão 2 min) | `POST /user` com e-mail único aleatório | Sim — SLO-1 e SLO-2 |
| `listagem` | `RATE` (padrão 50 req/s) | `DURATION` | `GET /user?page=0&size=20` | Sim — SLO-1 e SLO-3 |
| `ponta_a_ponta` | 2 cadastros/s | `DURATION` | `POST /user` e consulta `GET /emails/user/{id}` a cada 50 ms até o e-mail aparecer (limite de 10 s) | Sim — SLO-4 |

Os cenários `cadastro`, `listagem` e `ponta_a_ponta` rodam **simultaneamente** (carga mista, mais realista).

**Ambiente controlado:**
- Banco zerado antes de cada rodada (`docker compose down -v`) — a tabela cresce a cada teste e isso altera a listagem.
- Limite de 1 CPU e 768 MB por serviço Java (definido no `docker-compose.yml`).
- Nenhum outro programa pesado aberto durante o teste.

**Como reproduzir:**

```bash
docker compose down -v                       # zera os bancos
docker compose up --build -d                 # sobe tudo
docker compose ps                            # esperar api-gateway e as réplicas de ms-user e ms-email "healthy"
docker compose --profile loadtest run --rm k6                          # carga nominal: 50 req/s, 2 min
docker compose --profile loadtest run --rm -e RATE=100 -e DURATION=3m k6   # outras cargas
```

> O k6 termina com código de saída **99** quando algum SLO é violado. Isso é esperado num teste de capacidade — o relatório é gerado do mesmo jeito.

**Conferência independente:** o k6 fala com o `api-gateway` (ADR-0005), então a medição inclui o salto pela borda. Durante o teste, os percentis podem ser consultados no gateway, por rota, em
`http://localhost:8080/metrics/spring.cloud.gateway.requests.percentile?tag=routeId:ms-user&tag=phi:0.95`,
ou numa réplica do ms-user (sem porta no host) com
`docker compose exec --index 1 ms-user curl -s "localhost:8081/metrics/http.server.requests.percentile?tag=uri:/user&tag=method:POST&tag=phi:0.95"`
(Micrometer). Diferenças pequenas em relação ao k6 são normais: o k6 inclui rede, o gateway e a fila do Tomcat; cada réplica só vê a sua parte da carga.

## 4. Resultados — carga nominal (50 req/s por endpoint)

**Máquina:** _CPU, RAM, sistema operacional, versão do Docker_
**Data:** _preencher_

> Colar aqui a tabela de `docs/performance/resultados/resultado-k6.md`.

| Endpoint | Requisições | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Erros | SLO | Resultado |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `POST /user` | | | | | | | p95<200 / p99<500 | |
| `GET /user?size=20` | | | | | | | p95<150 / p99<300 | |

| Fluxo assíncrono | p50 (ms) | p95 (ms) | p99 (ms) | Processados em até 10 s | SLO | Resultado |
|---|---:|---:|---:|---:|---|---|
| Cadastro → e-mail registrado | | | | | ≥99% em 10 s / p95<2000 / p99<5000 | |

## 5. Teste de capacidade (recomendado)

Repetir com taxas crescentes para encontrar o ponto em que o SLO deixa de ser atendido (o "joelho" da curva):

| Taxa pedida (req/s) | Throughput obtido POST (req/s) | p95 POST (ms) | p99 POST (ms) | Erros | Iterações descartadas | SLO-2 |
|---:|---:|---:|---:|---:|---:|---|
| 50 | | | | | | |
| 100 | | | | | | |
| 200 | | | | | | |
| 400 | | | | | | |

"Iterações descartadas" > 0 ou throughput obtido menor que a taxa pedida indicam saturação (do serviço ou da máquina).

## 6. Conclusão sobre a aderência aos SLOs

> Escrever 1 parágrafo por SLO, com o número medido ao lado da meta. Modelo:

- **SLO-2 (latência do cadastro):** com 50 req/s, o `POST /user` teve p95 de **__ ms** e p99 de **__ ms**, contra metas de 200 ms e 500 ms → **atende / não atende**. _Se não atende: onde está o gargalo (CPU do container? pool de conexões? banco?) e qual ação o grupo propõe._
- **SLO-3 (listagem):** …
- **SLO-4 (entrega assíncrona):** …% dos e-mails registrados em até 10 s, p95 de __ ms → …
- **SLO-1 (erros):** taxa de falhas de __% → …
- **Capacidade:** o SLO-2 se mantém até ~__ req/s nesta máquina; acima disso …

## 7. Limitações da medição (ameaças à validade)

- **Gerador e sistema na mesma máquina:** o k6 disputa CPU com os serviços; os números são conservadores.
- **Docker Desktop (Windows/macOS)** roda containers dentro de uma VM, com sobrecarga de rede e disco.
- **Mailpit responde mais rápido que um SMTP real:** a latência ponta a ponta medida é otimista em relação à produção.
- **Duração curta:** 2 minutos de teste não equivalem à janela de 30 dias do SLO; o teste valida o comportamento sob carga, não a disponibilidade mensal.
- **Resolução de ~50 ms** na medição ponta a ponta (intervalo de consulta do k6).
