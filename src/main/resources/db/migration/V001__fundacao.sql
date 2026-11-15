-- Fundação: schemas dos módulos, função de imutabilidade e registro de eventos.

CREATE SCHEMA IF NOT EXISTS calculadora;
CREATE SCHEMA IF NOT EXISTS auth;

-- Usada pelos triggers de imutabilidade de cada módulo (00-arquitetura, terceira camada).
CREATE FUNCTION impedir_alteracao() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Registro imutável em %: crie uma versão nova', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

-- Event Publication Registry do Spring Modulith 2.1.1 (schemas/v2/schema-postgresql.sql).
-- Copiado da versão fixada no build: ao atualizar o Modulith, conferir se o script mudou.
CREATE TABLE event_publication
(
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
