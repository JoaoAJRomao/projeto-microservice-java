# ADR-0005: API Gateway e service discovery para escalar os serviços horizontalmente

- **Status:** Aceito
- **Data:** 2026-11-06
- **Decisores:** integrantes do grupo

## Contexto

Até aqui cada microsserviço rodava em **uma única instância**, publicada numa porta fixa do host (`ms-user` em `8081`, `ms-email` em `8082`). Isso trazia três problemas:

- **Não dava para escalar.** Uma segunda réplica não subia (`port is already allocated`) e, mesmo que subisse, o cliente não teria como alcançá-la: ele precisava saber o endereço de cada serviço e não havia balanceamento.
- **Ponto único de falha.** A queda da instância derrubava o endpoint inteiro. Por isso o SLO-1 ficou em 99,5% e o documento de arquitetura dizia que "prometer 99,9% exigiria réplicas".
- **Ninguém sabia quais instâncias estavam vivas.** Sem registro, não há como tirar do tráfego uma réplica que morreu.

Restrições: o ambiente continua subindo com **um comando** no Docker Compose (ADR-0004), sem Kubernetes; o grupo já domina Spring Boot; cada container Java tem 1 CPU e 768 MB; e o contrato público (`/user`, `/emails`) não deveria mudar para não quebrar os clientes atuais (k6, `demo.sh`, `demo.http`, roteiro da apresentação).

As mensagens do RabbitMQ já se distribuem sozinhas entre consumidores concorrentes (ADR-0001), então o problema é só o tráfego **HTTP**.

## Alternativas consideradas

### A) DNS do Docker Compose, sem gateway nem registro
O nome `ms-user` já resolve para todas as réplicas na rede do Compose.
- **Prós:** nenhum componente novo.
- **Contras:** o DNS não sabe se a réplica está viva; a JVM faz cache da resolução; o cliente continuaria precisando de uma porta por serviço; não atende ao requisito de service discovery da disciplina.

### B) Proxy reverso genérico na borda (Nginx ou Traefik)
- **Prós:** maduros, leves, bem documentados.
- **Contras:** o Nginx não conhece o registro de serviços (só DNS, com o mesmo problema da alternativa A); o Traefik descobre containers montando o socket do Docker no container, o que dá a ele controle total do Docker do host. Nenhum dos dois fala Problem Details nem propaga o Correlation ID do jeito do ADR-0003 sem configuração própria.

### C) Spring Cloud Gateway + Eureka (escolhida)
- **Prós:** mesmo stack Java/Spring do grupo; balanceamento client-side integrado ao registro (`lb://ms-user`); retry, timeout e circuit breaker (Resilience4j) por configuração; o filtro de Correlation ID e as respostas de erro seguem o mesmo padrão dos serviços.
- **Contras:** dois containers Java novos (~1 GB a mais de memória); mais uma camada de rede por requisição; o Eureka tem semântica própria de expiração, que exige ajuste para o ambiente de desenvolvimento.

### D) Consul no lugar do Eureka
- **Prós:** health check ativo pelo próprio registro; também serve de configuração centralizada.
- **Contras:** mais uma infraestrutura fora do stack Java para um ganho que o Eureka já entrega neste escopo.

## Decisão

Adotamos a **alternativa C**:

- **`api-gateway`** (Spring Cloud Gateway, variante WebFlux/Netty — um proxy que só faz I/O não precisa de uma thread por conexão) é a **única porta HTTP de aplicação no host** (`8080`). Rotas: `/user`, `/user/**` → `lb://ms-user` e `/emails`, `/emails/**` → `lb://ms-email`, com os mesmos caminhos de antes. Qualquer outro caminho responde `404`. `/health`, `/metrics` e `/info` são do próprio gateway.
- **`discovery-server`** (Eureka, instância única) mantém o registro. Cada réplica se registra ao subir com `instanceId` `<serviço>:<hostname do container>` e o IP da rede `edge`, e se descadastra ao receber SIGTERM.
- **Réplicas:** `ms-user` e `ms-email` perderam a porta no host e ganharam `deploy.replicas` (`MS_USER_REPLICAS`, `MS_EMAIL_REPLICAS`, padrão 2). Escalar: `docker compose up -d --scale ms-user=4`.
- **Resiliência no gateway:**
  - conexão em até 1 s, resposta em até 5 s; acima disso, `504`;
  - **retry só para `GET`**, uma vez, em outra réplica, para erro de conexão ou `502`/`503`. `POST` nunca é repetido: se a primeira tentativa chegou a gravar, repetir cadastraria de novo;
  - **circuit breaker** por rota: janela de 10 chamadas, abre com 50% de falhas (só erro de conexão e timeout contam; `4xx` e `500` do serviço passam direto), fica aberto 10 s respondendo `503` sem chamar o serviço e depois libera 3 chamadas de teste;
  - sem réplica registrada, `503`.
- **Formato de erro do gateway:** Problem Details (RFC 9457), igual ao dos serviços, com `type` explícito (`about:blank` — o Spring 7 omite o valor padrão, e o contrato traz o campo sempre):

```json
{"type":"about:blank","title":"Serviço indisponível","status":503,
 "detail":"Nenhuma instância de ms-user disponível no momento.",
 "instance":"/user","correlationId":"apresentacao-0001"}
```

  O `504` tem o mesmo formato, com `"title":"Tempo de resposta esgotado"` e `"detail":"ms-user não respondeu em 5 s."`.
- **Correlation ID na borda:** o gateway aplica a mesma regra do ADR-0003 (`^[A-Za-z0-9-]{8,64}$`, senão gera um UUID), repassa o ID à réplica, devolve no header da resposta e registra uma linha de acesso JSON (ECS) por requisição roteada.
- **Status no registro segue o ciclo de vida, não a saúde das dependências** (`eureka.client.healthcheck.enabled: false`): ligar o health check tiraria todas as réplicas do ar quando o RabbitMQ cai, e o cadastro funciona sem o broker (ADR-0001).

### Intervalos do Eureka (valores de desenvolvimento)

Os padrões do Eureka (heartbeat de 30 s, expiração de 90 s, caches de 30 s) fariam uma réplica nova levar mais de um minuto para receber tráfego. Usamos:

| Parâmetro | Valor | Efeito medido |
|---|---|---|
| Heartbeat (`lease-renewal-interval-in-seconds`) | 5 s | — |
| Expiração (`lease-expiration-duration-in-seconds`, `EUREKA_LEASE_EXPIRATION_S`) | 10 s | Réplica morta com `docker kill` sai do registro em até ~25 s |
| Varredura de despejo no servidor | 5 s | — |
| Cache de resposta do servidor | desligado | Registro novo visível na hora |
| Busca do registro pelo gateway + cache do LoadBalancer | 5 s + 5 s | Réplica nova recebe tráfego segundos depois de ficar pronta |
| Autopreservação | desligada | Com poucas réplicas, ela impediria qualquer despejo |

O Eureka só despeja uma instância depois de **duas vezes** a expiração sem heartbeat (comportamento conhecido do servidor). Com expiração de 15 s medimos 33 s para despejar uma réplica morta, acima da meta de 30 s; com 10 s, o pior caso fica em ~25 s. Em produção, com mais réplicas e rede menos previsível, os valores voltam para perto dos padrões e a autopreservação é religada.

### IP que cada réplica registra

As réplicas estão em três redes (`edge`, `broker-net` e a rede do banco), e a ordem das interfaces dentro do container é arbitrária: no primeiro teste, 3 de 4 réplicas registraram um IP que o gateway não alcança. Cada serviço tem um `EnvironmentPostProcessor` (`EurekaEnderecoDaRede`) que, antes de registrar, descobre o endereço local da rota até o `discovery-server` (que só está na `edge`) e o usa como `eureka.instance.ip-address`. Se o nome do registro não resolver, a réplica registra o hostname do container, que o DNS do Docker resolve para o IP da `edge`. Rejeitamos fixar a sub-rede da `edge` (colide com redes já existentes na máquina de cada integrante) e a `priority` de rede do Compose (o Docker 28 não respeita a ordem).

## Consequências

- **Positivas:**
  - o cliente fala com um endereço só e o número de réplicas muda sem ele saber;
  - a queda de uma réplica vira retry (`GET`) ou `503` controlado, nunca espera indefinida;
  - só o gateway recebe tráfego de fora: os serviços deixaram de ter porta no host;
  - o gateway é o lugar natural para autenticação e *rate limiting* no futuro.
- **Negativas / custos:**
  - dois containers Java a mais: o Docker precisa de pelo menos 6 GB de memória no padrão (2 + 2 réplicas);
  - um salto de rede a mais por requisição (o k6 passa a medir pelo gateway);
  - as métricas de cada réplica não ficam mais em `localhost:8081`; é preciso `docker compose exec` ou olhar as métricas por rota no gateway;
  - `docker compose logs ms-user` passa a intercalar réplicas (o prefixo diz qual).
- **Riscos aceitos e mitigação:**
  - **`api-gateway` e `discovery-server` são pontos únicos de falha.** Com o registro fora, o gateway continua roteando com a última lista conhecida, e as réplicas se registram de novo sozinhas quando ele volta. Redundância dos dois fica como evolução (`docs/arquitetura.md` §9, item 10);
  - **réplica morta sem aviso fica no registro por até ~25 s.** Nesse intervalo, `GET` é repetido em outra réplica e o circuit breaker corta falhas seguidas; `POST` pode receber `503`;
  - **o registro não autentica quem se registra.** Aceito porque a rede é local e o painel só escuta em `127.0.0.1`.

## Histórico de revisões

| Versão | Data | Mudança | Motivo |
|---|---|---|---|
| 1.0 | 2026-11-06 | Versão inicial | — |
