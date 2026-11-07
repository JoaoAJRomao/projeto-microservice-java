-- Esquema que o Hibernate gerava com ddl-auto: update (pg_dump --schema-only), ADR-0006.
-- Bancos criados antes do Flyway recebem baseline nesta versão e não executam este arquivo;
-- por isso os nomes das constraints são os mesmos que o Hibernate deu.

CREATE TABLE emails (
    email_id uuid NOT NULL,
    correlation_id character varying(64),
    email_from character varying(255),
    email_to character varying(255),
    send_date_email timestamp(6) without time zone,
    status_email smallint,
    subject character varying(255),
    text text,
    user_id uuid,
    CONSTRAINT emails_pkey PRIMARY KEY (email_id),
    CONSTRAINT emails_status_email_check CHECK (((status_email >= 0) AND (status_email <= 1)))
);
