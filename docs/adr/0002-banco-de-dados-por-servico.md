# ADR-0002: Banco de dados por serviço (PostgreSQL isolado por rede)

- **Status:** Aceito
- **Data:** _preencher_
- **Decisores:** _integrantes do grupo_

## Contexto

Os dois serviços têm dados de natureza diferente:

- **ms-user:** cadastro de usuários (`users`) — leitura frequente, regra de unicidade de e-mail;
- **ms-email:** histórico de envios (`emails`) — cresce a cada cadastro, só recebe `INSERT` e consultas.

Queremos que cada serviço possa mudar seu esquema, ser implantado e escalado sem coordenar com o outro, e que um problema no banco de um (lentidão, lock, disco cheio) não derrube o outro. Ambos já usavam PostgreSQL na versão inicial, cada um com seu `docker-compose.yml`.

## Alternativas consideradas

### A) Um banco compartilhado pelos dois serviços
- **Prós:** uma única instância para operar; *joins* e chave estrangeira entre `emails` e `users`.
- **Contras:** anti-pattern **"banco de integração"** — qualquer mudança de esquema pode quebrar o outro serviço; acoplamento escondido; os serviços deixam de ser implantáveis de forma independente; uma consulta pesada de um afeta o outro.

### B) Mesma instância PostgreSQL, *schemas* e usuários separados
- **Prós:** isolamento lógico com custo baixo de recursos; cada serviço com credencial própria.
- **Contras:** os serviços continuam compartilhando CPU, memória, conexões e disponibilidade da instância (falha de um afeta o outro); a tentação de "só um join" entre schemas continua existindo.

### C) Uma instância PostgreSQL por serviço
- **Prós:** isolamento completo de dados, falhas e recursos; cada serviço evolui o esquema livremente; espelha o que se faria em produção (um banco gerenciado por serviço).
- **Contras:** mais recursos (duas instâncias); sem *joins*/integridade referencial entre os dados; dados replicados por mensagem.

### D) Persistência poliglota (ex.: MongoDB para o histórico de e-mails)
- **Prós:** escolher o banco mais adequado a cada tipo de dado.
- **Contras:** o histórico de e-mails é tabular e simples; uma segunda tecnologia aumentaria o custo de aprendizado e operação sem ganho real.

## Decisão

Adotar a **alternativa C — um PostgreSQL 17 por serviço**:

- containers `user-db` e `email-db`, cada um com volume, banco e **usuário/senha próprios**;
- o isolamento é **imposto pela rede**: `user-db` está só na rede `user-net` (com o ms-user) e `email-db` só na `email-net` (com o ms-email). O ms-email não consegue nem abrir conexão com o banco de usuários;
- a única troca de dados entre os serviços é a mensagem do ADR-0001: o ms-email guarda uma **cópia** de `userId` e `emailTo` no próprio histórico.

## Consequências

**Positivas**
- Independência de esquema, implantação e escala.
- Isolamento de falhas: o ms-user continua cadastrando com o `email-db` fora do ar (as mensagens esperam na fila).
- Menor superfície de ataque: credenciais de um serviço não dão acesso aos dados do outro.

**Negativas / custos**
- Sem chave estrangeira entre `emails.user_id` e `users.user_id`: a referência é "lógica" e pode ficar órfã (ex.: se um dia houver exclusão de usuários, será preciso publicar um evento para o ms-email reagir).
- Dados duplicados (o e-mail do destinatário existe nos dois bancos) e consistência eventual entre eles.
- Consultas que cruzem usuários e e-mails precisam ser feitas por API (composição) e não por SQL.
- Duas instâncias de banco consomem mais memória no ambiente local.

**Riscos aceitos e mitigação**
- Esquemas criados com `ddl-auto: update` (sem histórico de migrações). Aceitável no desenvolvimento; evolução: **Flyway**.

## Histórico de revisões

| Versão | Data | Mudança | Motivo |
|---|---|---|---|
| 1.0 | _preencher_ | Versão inicial | — |
