-- Esquema criado pelo Hibernate (ddl-auto: update) antes do Flyway, tirado de
-- pg_dump --schema-only de um volume da versão anterior.
CREATE TABLE public.emails (
    email_id uuid NOT NULL,
    correlation_id character varying(64),
    email_from character varying(255),
    email_to character varying(255),
    send_date_email timestamp(6) without time zone,
    status_email smallint,
    subject character varying(255),
    text text,
    user_id uuid,
    CONSTRAINT emails_status_email_check CHECK (((status_email >= 0) AND (status_email <= 1)))
);

ALTER TABLE ONLY public.emails
    ADD CONSTRAINT emails_pkey PRIMARY KEY (email_id);
