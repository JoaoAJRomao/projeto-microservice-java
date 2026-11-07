# Resultado do teste de carga (gerado automaticamente pelo k6)

- **Data/hora (UTC):** 2026-11-06T23:36:53.920Z
- **Carga:** 50 req/s em POST /user + 50 req/s em GET /user, simultâneos, por 10s (após 20s de aquecimento); 2 cadastros/s acompanhados até o e-mail
- **Executor:** constant-arrival-rate (modelo aberto: a taxa não cai se o sistema ficar lento)
- **Máquina:** _preencher (CPU, RAM, SO, Docker Desktop/Engine)_

## Latência e throughput por endpoint

| Endpoint | Requisições | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | máx (ms) | Erros | Iterações descartadas | SLO | Resultado |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| POST /user | 501 | 50.1 | 30.7 | 309.4 | 405.2 | 812.5 | 0.00% | 0 | p95<200 / p99<500 | ❌ viola |
| GET /user?size=20 | 500 | 50.0 | 27.3 | 267.4 | 527.1 | 834.7 | 0.00% | 0 | p95<150 / p99<300 | ❌ viola |

## Fluxo assíncrono (cadastro → fila → e-mail registrado)

| Métrica | p50 (ms) | p95 (ms) | p99 (ms) | máx (ms) | Processados em até 10 s | Status SENT | SLO | Resultado |
|---|---:|---:|---:|---:|---:|---:|---|---|
| Latência ponta a ponta | 76.0 | 397.0 | 833.0 | 942.0 | 100.00% | 100.00% | p95<2000 / p99<5000 / ≥99% em 10 s | ✅ atende |

## Conclusão automática

**SLOs violados:**

- POST /user: p95 309.4 ms ≥ 200 ms
- GET /user?size=20: p95 267.4 ms ≥ 150 ms
- GET /user?size=20: p99 527.1 ms ≥ 300 ms

> "Iterações descartadas" > 0 indica que o k6 não conseguiu manter a taxa pedida — o sistema (ou a máquina) chegou ao limite.
