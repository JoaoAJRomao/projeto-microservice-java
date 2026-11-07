# Checklist de Peer Review — avaliação de outro grupo

> Preencher **durante a apresentação do grupo avaliado** e entregar ao professor ao final.
> Marque **S** (sim), **P** (parcial) ou **N** (não) e anote evidências — o que foi visto na tela, não o que foi dito.

| | |
|---|---|
| **Grupo avaliador** | |
| **Integrantes (avaliadores)** | |
| **Grupo avaliado** | |
| **Repositório avaliado** | |
| **Data** | |

---

## 1. Coerência arquitetural (25%)

| # | Item | S / P / N | Evidência / observação |
|---|---|:---:|---|
| 1.1 | O documento apresenta **contexto e problema** claros (o que o sistema faz e por que a arquitetura importa) | | |
| 1.2 | Cada decisão é **justificada pelo contexto** (requisitos, SLOs, restrições), não só pela tecnologia | | |
| 1.3 | O código e o `docker-compose.yml` **correspondem** ao que está documentado | | |
| 1.4 | Os **anti-patterns/trade-offs** existentes são reconhecidos (ex.: dual write, banco compartilhado, segredos no código, chamada síncrona em cadeia) | | |
| 1.5 | Estratégias de **observabilidade** e **segurança** estão descritas | | |

## 2. Qualidade dos ADRs (20%)

| # | Item | S / P / N | Evidência / observação |
|---|---|:---:|---|
| 2.1 | Há **no mínimo 3 ADRs** | | Quantidade: |
| 2.2 | Seguem o template: **contexto, alternativas, decisão, consequências** | | |
| 2.3 | Há **pelo menos 2 alternativas reais** por ADR, com prós e contras | | |
| 2.4 | As consequências incluem **pontos negativos/custos**, não só benefícios | | |
| 2.5 | Ao menos um ADR mostra **revisão após Peer Review** (histórico de revisões) | | |

## 3. Ambiente containerizado (25%)

| # | Item | S / P / N | Evidência / observação |
|---|---|:---:|---|
| 3.1 | `docker compose up --build` foi executado **ao vivo** | | |
| 3.2 | O **build** terminou sem erro | | Tempo aproximado: |
| 3.3 | Todos os containers ficaram **em execução / healthy** (`docker compose ps`) | | |
| 3.4 | Os serviços **se comunicam** (requisição em um gera efeito no outro) | | Como foi demonstrado: |
| 3.5 | Nenhuma credencial real exposta no `docker-compose.yml` / código | | |

## 4. Resultado de medição (15%)

| # | Item | S / P / N | Evidência / observação |
|---|---|:---:|---|
| 4.1 | **p50, p95 e p99** documentados para ao menos um endpoint | | Endpoint: |
| 4.2 | **Throughput** documentado | | Valor: |
| 4.3 | **Metodologia** explicada (ferramenta, carga, duração, aquecimento) | | |
| 4.4 | **Conclusão explícita** sobre atender ou não cada SLO | | |
| 4.5 | Os SLOs são **mensuráveis** (SLI, meta numérica, janela) | | |

## 5. Health check + logging (10%)

| # | Item | S / P / N | Evidência / observação |
|---|---|:---:|---|
| 5.1 | Endpoint **`/health`** implementado e demonstrado | | |
| 5.2 | Health check verifica **dependências** (banco, broker), não só "OK" | | |
| 5.3 | **Logs estruturados** (ex.: JSON) | | |
| 5.4 | **Correlation ID** presente nos logs | | |
| 5.5 | Correlation ID **propagado entre serviços** (mesmo ID nos dois) | | |

---

## 6. Síntese

**Pontos fortes (mínimo 2):**

1.
2.
3.

**Sugestões de melhoria (mínimo 3, específicas e acionáveis — o grupo avaliado deve poder incorporar ao menos uma):**

1.
2.
3.

**Pergunta(s) feita(s) ao grupo e resposta:**

-

**Nota sugerida por critério** (0 a 10, opcional):

| Coerência (25%) | ADRs (20%) | Ambiente (25%) | Medição (15%) | Health + log (10%) |
|:---:|:---:|:---:|:---:|:---:|
| | | | | |

**Assinaturas dos avaliadores:**

_______________________________ &nbsp;&nbsp; _______________________________ &nbsp;&nbsp; _______________________________
