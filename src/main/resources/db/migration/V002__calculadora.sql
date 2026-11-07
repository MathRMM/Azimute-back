-- Histórico de simulações da calculadora (02-modulo-calculadora, Schema).

CREATE TABLE calculadora.simulacoes (
    id                UUID PRIMARY KEY,
    bem               TEXT NOT NULL CHECK (btrim(bem) <> ''),
    data              DATE NOT NULL,
    local             TEXT,
    instituicao       TEXT,

    -- entrada informada
    valor_bem         NUMERIC(19,2) NOT NULL CHECK (valor_bem > 0),
    entrada           NUMERIC(19,2) NOT NULL CHECK (entrada >= 0),
    parcela           NUMERIC(19,2) NOT NULL CHECK (parcela > 0),
    prazo             INT NOT NULL CHECK (prazo > 0),

    -- saída calculada: SNAPSHOT, nunca recalculado na leitura
    valor_financiado  NUMERIC(19,2) NOT NULL,
    taxa_mensal       NUMERIC(12,8) NOT NULL,
    taxa_anual        NUMERIC(12,8) NOT NULL,
    total_pago        NUMERIC(19,2) NOT NULL,
    total_juros       NUMERIC(19,2) NOT NULL,

    observacao        TEXT,
    origem            TEXT NOT NULL DEFAULT 'PROPOSTA'
                      CHECK (origem IN ('PROPOSTA', 'ESTIMATIVA')),
    ancora_id         UUID REFERENCES calculadora.simulacoes (id),
    status            TEXT NOT NULL DEFAULT 'RECEBIDA'
                      CHECK (status IN ('RECEBIDA', 'DESCARTADA', 'ACEITA')),
    calculada_em      TIMESTAMPTZ NOT NULL,
    sincronizada_em   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT entrada_nao_supera_bem CHECK (entrada < valor_bem),
    CONSTRAINT estimativa_exige_ancora CHECK (origem <> 'ESTIMATIVA' OR ancora_id IS NOT NULL)
);

COMMENT ON COLUMN calculadora.simulacoes.id IS
    'Gerado no CLIENTE (UUIDv7), sem DEFAULT de propósito: o PUT /{id} idempotente depende disso. Não "consertar".';

CREATE INDEX simulacoes_bem_taxa_idx ON calculadora.simulacoes (bem, taxa_mensal);
CREATE INDEX simulacoes_data_idx ON calculadora.simulacoes (data DESC);
CREATE INDEX simulacoes_ancora_idx ON calculadora.simulacoes (ancora_id) WHERE ancora_id IS NOT NULL;

-- Valores são snapshot (00-arquitetura, triggers de imutabilidade): só observacao e status podem mudar.
CREATE FUNCTION calculadora.simulacao_valores_imutaveis() RETURNS TRIGGER AS $$
BEGIN
    IF (NEW.valor_bem, NEW.entrada, NEW.parcela, NEW.prazo, NEW.valor_financiado,
        NEW.taxa_mensal, NEW.taxa_anual, NEW.total_pago, NEW.total_juros)
       IS DISTINCT FROM
       (OLD.valor_bem, OLD.entrada, OLD.parcela, OLD.prazo, OLD.valor_financiado,
        OLD.taxa_mensal, OLD.taxa_anual, OLD.total_pago, OLD.total_juros)
    THEN
        RAISE EXCEPTION 'Valores de simulação são imutáveis. Registre nova simulação.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER simulacoes_valores_imutaveis
    BEFORE UPDATE ON calculadora.simulacoes
    FOR EACH ROW EXECUTE FUNCTION calculadora.simulacao_valores_imutaveis();
