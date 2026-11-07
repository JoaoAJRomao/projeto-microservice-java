# ADR-0001: Comunicação assíncrona entre ms-user e ms-email via RabbitMQ

- **Status:** Aceito
- **Data:** _preencher_
- **Decisores:** _integrantes do grupo_

## Contexto

Todo cadastro de usuário precisa gerar um e-mail de boas-vindas. O envio depende de um servidor SMTP externo, que:

- é **lento** em relação ao cadastro (centenas de ms a segundos por mensagem, contra poucos ms de um `INSERT`);
- pode ficar **indisponível** ou impor **limites de envio**;
- não precisa acontecer no mesmo instante do cadastro: o usuário aceita receber o e-mail alguns segundos depois.

Requisitos que pesam na decisão (ver `docs/arquitetura.md`): o cadastro deve ter **p95 < 200 ms** (SLO-2) e continuar disponível mesmo com o SMTP fora do ar (SLO-1); nenhum pedido de e-mail pode se perder; 99% dos e-mails devem ser processados em até 10 s (SLO-4).

O grupo já conhecia RabbitMQ das aulas, e a versão inicial do projeto já usava uma instância no CloudAMQP.

## Alternativas consideradas

### A) Chamada REST síncrona do ms-user para o ms-email
- **Prós:** simples de entender e depurar; o cliente sabe na hora se o e-mail saiu.
- **Contras:** **acoplamento temporal** — se o ms-email ou o SMTP estiverem lentos/fora, o cadastro fica lento/falha (falha em cascata); a latência do SMTP entra no p95 do cadastro; retry e circuit breaker teriam de ser implementados no ms-user; picos de cadastro viram picos no SMTP, sem amortecimento.

### B) Mensageria com RabbitMQ (fila durável)
- **Prós:** desacopla disponibilidade e latência dos dois serviços; a fila funciona como **buffer** em picos; mensagens persistem enquanto o ms-email está parado; ACK, retry e Dead Letter Queue prontos no broker/Spring AMQP; equipe já domina a tecnologia.
- **Contras:** **consistência eventual** (o e-mail chega depois); mais um componente de infraestrutura para operar; gravar no banco e publicar no broker não é atômico (*dual write*); depurar fluxo assíncrono exige rastreamento (resolvido no ADR-0003).

### C) Apache Kafka (log de eventos)
- **Prós:** altíssimo throughput, retenção e *replay* de eventos, vários consumidores independentes.
- **Contras:** complexidade operacional desproporcional ao volume (dezenas de mensagens/s); ambiente local mais pesado; curva de aprendizado; recursos como *replay* não são necessários para e-mail de boas-vindas.

### D) ms-email lendo periodicamente a tabela de usuários (polling)
- **Prós:** nenhuma infraestrutura nova.
- **Contras:** viola o banco por serviço (ADR-0002); atraso proporcional ao intervalo de polling; carga constante no banco do ms-user.

## Decisão

Adotar a **alternativa B — RabbitMQ**, com os seguintes detalhes de implementação:

1. **Fila durável** `email.boas-vindas`, publicada pela *exchange* padrão (routing key = nome da fila). Corpo JSON (`JacksonJsonMessageConverter`).
2. **Publicação somente após o commit:** o `UserService` dispara um evento interno e o `UserCreatedListener` (`@TransactionalEventListener(phase = AFTER_COMMIT)`) publica no broker. Assim nunca sai e-mail para um usuário cujo `INSERT` sofreu rollback.
3. **Falha ao publicar não derruba o cadastro:** a exceção é capturada e registrada em log de nível ERROR com o `correlationId` (risco aceito, ver Consequências).
4. **Retry no consumidor:** até 3 tentativas com backoff exponencial (1 s, 2 s). Esgotadas as tentativas, a mensagem é rejeitada sem *requeue* e o RabbitMQ a move para a **DLQ** `email.boas-vindas.dlq` — sem loop infinito de *poison message*.
5. **Falha de SMTP não é exceção:** o ms-email registra o envio com status `ERROR` e confirma (ACK) a mensagem; retry/DLQ ficam para falhas de infraestrutura (ex.: banco do ms-email fora).
6. **Fila declarada pelos dois serviços** (declaração idempotente com argumentos idênticos): se o ms-user subir e publicar antes do ms-email existir, a mensagem já encontra a fila e fica guardada.
7. **Consumo concorrente:** 2 a 4 consumidores (`CONSUMER_CONCURRENCY`/`CONSUMER_MAX_CONCURRENCY`), já que a ordem dos e-mails não importa.
8. Broker **local no Docker Compose**; continua possível apontar para o CloudAMQP via `RABBITMQ_ADDRESSES` (ver ADR-0004).

## Consequências

**Positivas**
- O cadastro não espera o SMTP: a latência do `POST /user` fica limitada a banco + publicação local no broker.
- SMTP ou ms-email fora do ar não impedem cadastros — os pedidos se acumulam na fila e são processados quando o consumidor volta.
- Picos de cadastro são absorvidos pela fila; o ms-email pode ser escalado com mais consumidores.
- Falhas persistentes ficam visíveis e recuperáveis na DLQ (UI do RabbitMQ).

**Negativas / custos**
- Consistência eventual: a resposta `201` não garante que o e-mail já saiu (o cliente pode consultar `GET /emails/user/{id}`).
- Mais um componente de infraestrutura (RabbitMQ) para subir, monitorar e proteger.
- Os dois serviços precisam manter a declaração da fila idêntica; uma divergência de argumentos gera erro `PRECONDITION_FAILED`.

**Riscos aceitos e mitigação**
- *Dual write:* se o broker cair exatamente entre o commit e a publicação, o usuário fica salvo **sem** e-mail. Mitigação atual: log de erro com `correlationId` (permite reprocessar manualmente). Solução definitiva: **Transactional Outbox**.
- *Entrega "pelo menos uma vez":* se o SMTP aceitar a mensagem e o `INSERT` do histórico falhar, o retry pode enviar o e-mail duas vezes. Solução futura: chave de idempotência.
- *Mensagem de comando:* o ms-user define assunto e texto do e-mail, o que acopla o conteúdo ao produtor. Evolução: publicar um evento de domínio (`UsuarioCriado`) e deixar o conteúdo para o ms-email.

## Histórico de revisões

| Versão | Data | Mudança | Motivo |
|---|---|---|---|
| 1.0 | _preencher_ | Versão inicial | — |
