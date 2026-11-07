-- Autenticação de usuário único: senha uma vez, token por dispositivo depois (01-modulo-auth).

CREATE TABLE auth.usuarios (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    login          TEXT NOT NULL UNIQUE,
    senha_hash     TEXT NOT NULL,              -- BCrypt, custo 12
    criado_em      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ultimo_acesso  TIMESTAMPTZ
);

CREATE TABLE auth.dispositivos (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    nome          TEXT NOT NULL,
    token_hash    TEXT NOT NULL UNIQUE,        -- SHA-256 do token; o token nunca é guardado
    criado_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ultimo_uso    TIMESTAMPTZ,
    revogado_em   TIMESTAMPTZ                  -- NULL = ativo
);

-- Usada pelo limite de tentativas (Fase 8 do SPEC).
CREATE TABLE auth.tentativas_login (
    id       BIGSERIAL PRIMARY KEY,
    ip       INET NOT NULL,
    em       TIMESTAMPTZ NOT NULL DEFAULT now(),
    sucesso  BOOLEAN NOT NULL
);

CREATE INDEX tentativas_login_ip_em_idx ON auth.tentativas_login (ip, em DESC);
CREATE INDEX tentativas_login_em_idx ON auth.tentativas_login (em DESC) WHERE NOT sucesso;
