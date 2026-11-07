# ADR-0004: Ambiente containerizado local e segredos por variáveis de ambiente

- **Status:** Aceito
- **Data:** _preencher_
- **Decisores:** _integrantes do grupo_

## Contexto

Situação da versão inicial:

- cada serviço tinha seu próprio `docker-compose.yml` subindo **apenas o PostgreSQL**; as aplicações rodavam pela IDE;
- o RabbitMQ era uma instância **na nuvem (CloudAMQP)** e o SMTP um servidor de testes externo — ambos exigem internet e estão sujeitos a cotas do plano gratuito;
- **usuários e senhas do CloudAMQP, do SMTP e dos bancos estavam escritos no `application.yaml`** e foram versionados no GitHub.

Requisito da disciplina: um `docker-compose.yml` que **sobe o ambiente completo, com build, ao vivo na apresentação**, e serviços que se comunicam. Riscos para a apresentação: rede instável da universidade, cota do broker em nuvem, e qualquer integrante precisar das credenciais reais para rodar.

## Alternativas consideradas

### Ambiente

**A) Manter os serviços em nuvem (CloudAMQP + SMTP externo); Compose só builda as aplicações e os bancos**
- **Prós:** igual à versão inicial; broker gerenciado.
- **Contras:** demonstração depende de internet e de cotas de terceiros; cada máquina precisa das credenciais reais; e-mails de teste saem para fora.

**B) Tudo local num único Compose: RabbitMQ, Mailpit (SMTP falso com caixa de entrada web), dois PostgreSQL e os dois serviços buildados por Dockerfile**
- **Prós:** reproduzível em qualquer máquina com Docker; funciona sem internet depois do primeiro download das imagens; nenhum e-mail real é enviado; a caixa de entrada do Mailpit e a UI do RabbitMQ ajudam a demonstrar o fluxo.
- **Contras:** primeiro build demora (download das dependências Maven); ambiente local difere da produção (SMTP falso, AMQP sem TLS).

**C) Kubernetes local (kind/minikube)**
- **Prós:** mais próximo de produção (probes, réplicas).
- **Contras:** pesado para notebooks e para o tempo da apresentação; complexidade além do escopo.

### Segredos

**D) Continuar com valores no `application.yaml`** — anti-pattern: segredo no repositório (e no histórico do Git para sempre).

**E) Variáveis de ambiente, com padrões apenas de desenvolvimento, `.env` fora do Git e `.env.example` documentado**
- **Prós:** padrão *12-factor*; mesma imagem roda em qualquer ambiente mudando só variáveis; simples.
- **Contras:** variáveis de ambiente podem vazar por `docker inspect`/logs de diagnóstico; não há rotação automática.

**F) Cofre de segredos (Vault, AWS Secrets Manager) ou Docker/Kubernetes secrets**
- **Prós:** rotação, auditoria, controle de acesso.
- **Contras:** infraestrutura adicional sem benefício no ambiente local.

## Decisão

Adotar **B** para o ambiente e **E** para os segredos.

**docker-compose.yml único na raiz**
- Serviços: `rabbitmq` (4-management), `user-db` e `email-db` (postgres:17-alpine), `mailpit`, `discovery-server`, `api-gateway`, `ms-user` e `ms-email` (2 réplicas de cada) e `k6` (só no perfil `loadtest`).
- Ordem de subida por `healthcheck` + `depends_on: condition: service_healthy`; o ms-user não depende do ms-email (ADR-0001).
- Redes separadas (`edge`, `broker-net`, `user-net`, `email-net`) reforçando o ADR-0002.
- Portas de infraestrutura publicadas apenas em `127.0.0.1`; a API entra só pelo `api-gateway` em 8080 (ADR-0005).
- Limite de 1 CPU e 768 MB por réplica de serviço Java (512 MB para gateway e registro), para medições comparáveis.

**Dockerfile multi-stage em cada serviço**
- Estágio de build com Maven + JDK 21 (cache do `~/.m2` via BuildKit); imagem final só com **JRE 21**, executando como **usuário não-root**.

**Configuração**
- `application.yaml` usa `${VARIAVEL:padrão-de-dev}` para banco, broker, SMTP e remetente; nenhum segredo real no repositório.
- O Compose funciona **sem** `.env` (padrões de desenvolvimento); `.env.example` lista tudo o que pode ser trocado; `.env` está no `.gitignore`.
- Para usar CloudAMQP ou um SMTP real basta definir `RABBITMQ_ADDRESSES=amqps://...` e `MAIL_HOST/MAIL_PORT/MAIL_USERNAME/MAIL_PASSWORD/MAIL_SMTP_AUTH/MAIL_SMTP_STARTTLS`.

## Consequências

**Positivas**
- `docker compose up --build -d` sobe todo o sistema em qualquer máquina do grupo — atende ao requisito da apresentação ao vivo.
- Nenhuma credencial real circula pelo repositório.
- Demonstração visual: caixa de entrada (http://localhost:8025) e filas (http://localhost:15672).
- Teste de carga roda com o mesmo comando em qualquer sistema operacional (k6 em container).

**Negativas / custos**
- Primeiro build leva alguns minutos (dependências Maven e imagens). **Mitigação:** rodar `docker compose build` e `docker compose pull` antes da apresentação.
- Paridade parcial com produção: Mailpit em vez de SMTP real; AMQP sem TLS dentro da rede Docker.
- Os padrões de desenvolvimento (ex.: `app_dev`) são públicos — **não** podem ser usados fora da máquina local.

**Ação obrigatória decorrente desta decisão**
- As credenciais do CloudAMQP e do SMTP que estavam nos repositórios antigos continuam no histórico do Git: devem ser **revogadas e substituídas** no painel de cada provedor.

## Histórico de revisões

| Versão | Data | Mudança | Motivo |
|---|---|---|---|
| 1.0 | _preencher_ | Versão inicial | — |
| 1.1 | 2026-11-06 | Lista de serviços, portas e limites do Compose atualizados: `api-gateway` e `discovery-server` novos, serviços com réplicas e sem porta no host | ADR-0005 |
