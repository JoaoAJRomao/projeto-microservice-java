# ADR-0003: Observabilidade com logs JSON, Correlation ID e health checks do Actuator

- **Status:** Aceito
- **Data:** _preencher_
- **Decisores:** _integrantes do grupo_

## Contexto

Com a comunicação assíncrona (ADR-0001), um único cadastro gera eventos em **dois processos diferentes**, em momentos diferentes e com dezenas de outros cadastros acontecendo ao mesmo tempo. Na versão inicial os logs eram texto livre do Spring, sem nenhum identificador comum: diante de "não recebi meu e-mail", não havia como ligar a requisição HTTP do ms-user à mensagem processada pelo ms-email.

Também precisamos de um endpoint de **health check** confiável: o Docker Compose só deve considerar um serviço pronto quando o banco e o broker estiverem acessíveis, e um orquestrador futuro (Kubernetes) precisa distinguir "processo travado" (reiniciar) de "dependência fora" (tirar do balanceamento).

Restrição: a solução deve ser leve — sem novos containers de infraestrutura — para caber no ambiente local e no tempo da disciplina.

## Alternativas consideradas

### Logs e rastreamento

**A) Manter logs em texto livre, sem identificador** — sem esforço, mas inviabiliza investigar o fluxo assíncrono sob concorrência.

**B) Correlation ID próprio (filtro HTTP + MDC + header AMQP) e logs JSON nativos do Spring Boot (formato ECS)**
- **Prós:** nenhuma dependência ou container novo; um `grep` pelo ID mostra a história completa nos dois serviços; JSON é indexável por qualquer ferramenta (Elastic, Loki, CloudWatch).
- **Contras:** código de propagação escrito à mão (e duplicado nos dois serviços); não mede o tempo de cada etapa como um trace faria.

**C) Rastreamento distribuído com OpenTelemetry / Micrometer Tracing + Jaeger ou Zipkin**
- **Prós:** padrão de mercado (W3C `traceparent`); spans com duração de cada etapa e visualização em cascata; propagação automática por HTTP e AMQP.
- **Contras:** mais containers (coletor, backend de traces), mais configuração e mais conceitos para a equipe dominar; desproporcional para dois serviços nesta fase.

**D) JSON via biblioteca externa (`logstash-logback-encoder`)**
- **Contras:** redundante — o Spring Boot já gera JSON estruturado nativamente desde a versão 3.4.

### Health check

**E) Endpoint próprio `@GetMapping("/health")` retornando `"OK"`** — trivial, mas diz apenas que o processo responde; não verifica banco nem broker.

**F) Spring Boot Actuator** — indicadores prontos para PostgreSQL (`db`) e RabbitMQ (`rabbit`), grupos `liveness`/`readiness`, além de métricas Micrometer.

## Decisão

Adotar **B + F**:

**Correlation ID**
- Header `X-Correlation-ID`. Se o cliente enviar um valor que case com `^[A-Za-z0-9-]{8,64}$`, ele é reaproveitado; senão, gera-se um UUID (a validação evita *log injection*).
- `CorrelationIdFilter` é o primeiro filtro da cadeia: coloca o ID no **MDC** (chave `correlationId`), devolve-o no header da resposta e grava um log de acesso (método, caminho, status, duração).
- O `UserProducer` copia o ID para o header AMQP `X-Correlation-ID` e para a propriedade `correlation_id`.
- O `EmailConsumer` lê o header, coloca no MDC durante o processamento e o ID é **persistido** na coluna `correlation_id` da tabela `emails`.
- Toda resposta HTTP traz o header `X-Correlation-ID`; o erro de negócio `409` (Problem Details) também o inclui no corpo.

**Logs estruturados**
- `logging.structured.format.console: ecs` nos dois serviços: cada linha é um JSON com `service.name`, nível, logger, thread, mensagem e os campos do MDC (incluindo `correlationId`).
- E-mails de usuários são mascarados nos logs (`m***@dominio.com`).

**Health checks e métricas (Actuator)**
- Endpoints na raiz: `/health`, `/health/liveness`, `/health/readiness`, `/info`, `/metrics`.
- `readiness` = `readinessState` + `db` + `rabbit`. O indicador de SMTP fica **fora** (SMTP fora não torna o ms-email inútil: ele registra `ERROR`).
- O `healthcheck` do Docker Compose usa `/health/readiness`.
- Percentis p50/p95/p99 de `http.server.requests` publicados pelo próprio serviço, para conferir a medição externa do k6.

## Consequências

**Positivas**
- Investigar um cadastro vira um comando: `docker compose logs ms-user ms-email | grep <correlationId>`; o ID também aparece na resposta HTTP e no histórico de e-mails.
- O Compose só libera o tráfego/dependentes quando banco e broker respondem de fato.
- Nenhum container ou dependência extra além do Actuator.
- Logs prontos para serem enviados a uma plataforma de busca no futuro.

**Negativas / custos**
- Classes `CorrelationId` e `CorrelationIdFilter` duplicadas nos dois serviços (decisão consciente: com dois serviços, uma biblioteca compartilhada criaria acoplamento de versão).
- Sem spans: para saber onde o tempo foi gasto entre os serviços, só pelos *timestamps* dos logs.
- JSON é menos legível no terminal (usar `jq` ou a busca por ID).
- `/health` com detalhes expõe informação interna: em produção usar `HEALTH_SHOW_DETAILS=when-authorized`.

**Evolução prevista**
- Migrar para **OpenTelemetry** quando houver mais serviços ou necessidade de medir latência por etapa. O Correlation ID pode ser mantido como *baggage* ou substituído pelo `traceId`.

## Histórico de revisões

| Versão | Data | Mudança | Motivo |
|---|---|---|---|
| 1.0 | _preencher_ | Versão inicial | — |
