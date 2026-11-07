# Architecture Decision Records (ADRs)

Cada ADR registra **uma** decisão arquitetural relevante, no template usado em aula:
**Contexto → Alternativas consideradas → Decisão → Consequências**, mais status e histórico de revisões.

| ADR | Título | Status |
|---|---|---|
| [0001](0001-comunicacao-assincrona-com-rabbitmq.md) | Comunicação assíncrona entre ms-user e ms-email via RabbitMQ | Aceito |
| [0002](0002-banco-de-dados-por-servico.md) | Banco de dados por serviço (PostgreSQL isolado por rede) | Aceito |
| [0003](0003-observabilidade-correlation-id-e-logs-estruturados.md) | Observabilidade: logs JSON, Correlation ID e health checks | Aceito |
| [0004](0004-ambiente-containerizado-e-segredos-por-variaveis-de-ambiente.md) | Ambiente containerizado local e segredos por variáveis de ambiente | Aceito |
| [0005](0005-api-gateway-e-service-discovery.md) | API Gateway e service discovery para escalar os serviços horizontalmente | Aceito |
| [0006](0006-migracoes-de-esquema-com-flyway.md) | Esquema dos bancos versionado com migrações Flyway | Aceito |

Modelo para novas decisões: [`0000-template.md`](0000-template.md).

**Regras do grupo**

- ADR aceito não é apagado: se a decisão mudar, cria-se um novo ADR com status "Substitui ADR-XXXX" e o antigo passa a "Substituído por ADR-YYYY".
- Ajustes de texto ou de detalhe (por exemplo, após o Peer Review) entram na tabela **Histórico de revisões** do próprio ADR, dizendo o que mudou e por quê.
