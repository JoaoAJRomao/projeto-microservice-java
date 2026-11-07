-- Esquema que o Hibernate gerava com ddl-auto: update (pg_dump --schema-only), ADR-0006.
-- Bancos criados antes do Flyway recebem baseline nesta versão e não executam este arquivo;
-- por isso os nomes das constraints são os mesmos que o Hibernate deu.

CREATE TABLE users (
    user_id uuid NOT NULL,
    email character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    CONSTRAINT users_pkey PRIMARY KEY (user_id),
    CONSTRAINT uk6dotkott2kjsp8vw4d0m25fb7 UNIQUE (email)
);
