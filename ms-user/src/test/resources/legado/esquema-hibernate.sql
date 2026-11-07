-- Esquema criado pelo Hibernate (ddl-auto: update) antes do Flyway, tirado de
-- pg_dump --schema-only de um volume da versão anterior.
CREATE TABLE public.users (
    user_id uuid NOT NULL,
    email character varying(255) NOT NULL,
    name character varying(255) NOT NULL
);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk6dotkott2kjsp8vw4d0m25fb7 UNIQUE (email);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (user_id);
