# ADR-0006: Esquema dos bancos versionado com migrações Flyway

- **Status:** Aceito
- **Data:** 2026-11-06
- **Decisores:** integrantes do grupo

## Contexto

O esquema de `user-db` e `email-db` era criado pelo Hibernate na subida de cada serviço (`spring.jpa.hibernate.ddl-auto: update`). Isso funcionou com uma instância, mas deixou de servir quando os serviços passaram a ter réplicas (ADR-0005):

- **Réplicas subindo juntas disputam o esquema.** Cada uma compara as entidades com o banco e emite `CREATE TABLE`/`ALTER TABLE` por conta própria, sem coordenação.
- **Nenhuma mudança fica registrada.** Não há como saber qual versão do esquema um banco tem, nem reproduzir exatamente o mesmo esquema em outra máquina.
- **O `update` só acrescenta.** Ele não remove colunas nem muda tipos, e pode criar uma coluna com nome errado se alguém renomear um campo na entidade, sem nenhum aviso.

Restrições: os bancos que os integrantes já têm (volumes Docker criados pelo `update`, com dados) precisam continuar funcionando sem apagar nada; o esquema é pequeno (uma tabela por serviço); e cada serviço tem o próprio banco (ADR-0002), então cada um versiona o seu.

## Alternativas consideradas

### A) Manter `ddl-auto: update`
- **Prós:** zero trabalho; o esquema acompanha as entidades sozinho.
- **Contras:** os três problemas do contexto continuam; nenhuma ferramenta de produção recomenda isso.

### B) Liquibase
- **Prós:** changelogs independentes de banco; *rollback* na versão gratuita.
- **Contras:** XML/YAML a mais para descrever duas tabelas; o grupo já escreve SQL, e os bancos são todos PostgreSQL.

### C) Flyway com scripts SQL, executado pelo próprio serviço na subida (escolhida)
- **Prós:** migração é um arquivo `.sql` comum, revisável no código; integração nativa do Spring Boot; o Flyway trava o banco durante a migração (*advisory lock* do PostgreSQL), então réplicas subindo juntas não disputam; a tabela `flyway_schema_history` registra o que foi aplicado.
- **Contras:** a versão gratuita não desfaz migrações; arquivo já aplicado não pode ser editado.

### D) Flyway num container separado, que roda antes dos serviços
- **Prós:** a aplicação não precisa de permissão de DDL; migração vira etapa explícita da implantação.
- **Contras:** mais um container e mais uma dependência de ordem no Compose, para um ganho que o lock do Flyway já cobre neste escopo.

## Decisão

Adotamos a **alternativa C** nos dois serviços:

- dependências `spring-boot-starter-flyway` e `org.flywaydb:flyway-database-postgresql`;
- `src/main/resources/db/migration/V1__esquema_inicial.sql` reproduz **exatamente** o esquema que o Hibernate gerava, inclusive os nomes das constraints (tirado de `pg_dump --schema-only` de um volume existente). Assim, as restrições continuam as mesmas: `users.email` único e obrigatório, `emails.correlation_id` com até 64 caracteres, `status_email` limitado aos valores do enum;
- `spring.jpa.hibernate.ddl-auto: validate`: o Hibernate não altera mais o esquema, só confere que ele bate com as entidades e **recusa subir** se divergir;
- `spring.flyway.baseline-on-migrate: true` e `baseline-version: 1`: num banco que já tem as tabelas (volume criado pela versão anterior), o Flyway marca a versão 1 como aplicada sem executar o script e sem tocar nos dados. Num banco vazio, executa a V1.

Mudanças futuras de esquema entram como **novos arquivos** (`V2__descricao.sql`, `V3__...`), nunca editando um já aplicado.

Verificação automatizada: testes com PostgreSQL real (Testcontainers) cobrem banco vazio, banco legado com dados e cadastro concorrente com o mesmo e-mail (um `201` e um `409`, pela constraint `UNIQUE`). Um cenário contra o Docker Compose sobe duas réplicas ao mesmo tempo sobre um banco vazio e confere que a V1 consta uma única vez em `flyway_schema_history`.

## Consequências

- **Positivas:**
  - réplicas sobem juntas sem disputa: uma migra, as outras esperam o lock e encontram o esquema pronto;
  - o esquema de qualquer banco é conhecido (`SELECT * FROM flyway_schema_history`) e reproduzível em qualquer máquina;
  - divergência entre entidade e banco aparece na subida, não em produção no meio de uma requisição;
  - os volumes existentes foram adotados sem migração de dados.
- **Negativas / custos:**
  - toda mudança em entidade exige escrever o SQL correspondente;
  - cada banco ganha a tabela `flyway_schema_history`;
  - o usuário do banco de cada serviço continua precisando de permissão de DDL (alternativa D a tiraria).
- **Riscos aceitos e mitigação:**
  - **sem rollback automático:** corrige-se com uma migração nova (`V<n+1>`); em produção, backup antes de migrar (`docs/arquitetura.md` §9, item 7);
  - **editar uma migração já aplicada quebra a subida** (`Migrations have failed validation`): regra do grupo é nunca editar; em desenvolvimento, `docker compose down -v` recria os bancos.

## Histórico de revisões

| Versão | Data | Mudança | Motivo |
|---|---|---|---|
| 1.0 | 2026-11-06 | Versão inicial | — |
