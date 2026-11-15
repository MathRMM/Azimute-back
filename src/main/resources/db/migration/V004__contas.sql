-- Módulo contas, parte 1 (PRD de contas, SPEC de contas §6.1): contas, movimentações e conciliações.
-- Datas de controle (RN14): created_at em todas; updated_at onde o registro muda; deleted_at onde há exclusão lógica.
-- updated_at é preenchido pelo SQL da aplicação, não por trigger (decisão 14 da doc 00, T8).

CREATE SCHEMA contas;

CREATE TABLE contas.contas (
    id                      UUID PRIMARY KEY DEFAULT uuidv7(),
    apelido                 TEXT NOT NULL CHECK (btrim(apelido) <> ''),
    instituicao             TEXT NOT NULL,
    tipo                    TEXT NOT NULL
                            CHECK (tipo IN ('CORRENTE', 'POUPANCA', 'INVESTIMENTO', 'CARTEIRA', 'BENEFICIO')),
    funcao                  TEXT NOT NULL CHECK (funcao IN ('GIRO', 'RESERVA')),
    limite_cheque_especial  NUMERIC(19,2) NOT NULL DEFAULT 0,
    ativa                   BOOLEAN NOT NULL DEFAULT true,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Cheque especial só existe em conta corrente (RN11, T7).
    CONSTRAINT limite_so_em_corrente CHECK (
        limite_cheque_especial >= 0 AND (tipo = 'CORRENTE' OR limite_cheque_especial = 0))
);

-- Sem deleted_at: conta não é excluída, é desativada (a conciliação inicial não pode sumir).
-- Apelido único ignorando maiúsculas e espaços nas pontas (RN09, T6).
CREATE UNIQUE INDEX contas_apelido_unico ON contas.contas (lower(btrim(apelido)));

CREATE TABLE contas.movimentacoes (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    tipo            TEXT NOT NULL
                    CHECK (tipo IN ('ENTRADA', 'SAIDA', 'TRANSFERENCIA', 'PAGAMENTO_FATURA', 'RENDIMENTO')),
    conta_origem    UUID REFERENCES contas.contas (id),
    conta_destino   UUID REFERENCES contas.contas (id),
    valor           NUMERIC(19,2) NOT NULL CHECK (valor > 0),
    data            DATE NOT NULL,
    ganho_id        UUID,
    gasto_id        UUID,
    cartao_id       UUID,
    fatura_comp     DATE CHECK (fatura_comp IS NULL OR EXTRACT(DAY FROM fatura_comp) = 1),
    motivo          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,

    -- A constraint mais importante do módulo (RN04): impossível uma transferência sem destino ou uma saída
    -- com destino, o tipo de dado corrompido que produz saldo errado sem erro visível.
    CONSTRAINT direcao_coerente CHECK (
        (tipo IN ('ENTRADA', 'RENDIMENTO')      AND conta_destino IS NOT NULL AND conta_origem IS NULL) OR
        (tipo IN ('SAIDA', 'PAGAMENTO_FATURA')  AND conta_origem  IS NOT NULL AND conta_destino IS NULL) OR
        (tipo = 'TRANSFERENCIA' AND conta_origem IS NOT NULL AND conta_destino IS NOT NULL
                                AND conta_origem <> conta_destino))
);

COMMENT ON COLUMN contas.movimentacoes.id IS
    'Vem do CLIENTE (UUIDv7) no PUT da API (idempotência, P1); o DEFAULT só serve à fachada entre módulos.';

-- Só as movimentações vivas: as excluídas não entram em cálculo nem impedem relançar a mesma referência (T5).
CREATE INDEX movimentacoes_origem_idx  ON contas.movimentacoes (conta_origem, data DESC)  WHERE deleted_at IS NULL;
CREATE INDEX movimentacoes_destino_idx ON contas.movimentacoes (conta_destino, data DESC) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX movimentacoes_gasto_unico  ON contas.movimentacoes (gasto_id)
    WHERE gasto_id IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX movimentacoes_ganho_unico  ON contas.movimentacoes (ganho_id)
    WHERE ganho_id IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX movimentacoes_fatura_unica ON contas.movimentacoes (cartao_id, fatura_comp)
    WHERE cartao_id IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE contas.conciliacoes (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    conta_id        UUID NOT NULL REFERENCES contas.contas (id),
    data            DATE NOT NULL,
    saldo_real      NUMERIC(19,2) NOT NULL,
    saldo_calculado NUMERIC(19,2) NOT NULL,
    -- Fato histórico do momento da conciliação, nunca recalculado (RN07).
    divergencia     NUMERIC(19,2) NOT NULL,
    justificativa   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conta_id, data)
);

CREATE INDEX conciliacoes_conta_data_idx ON contas.conciliacoes (conta_id, data DESC);

-- Imutável (00-arquitetura, triggers de imutabilidade): justificativa errada se corrige com nova conciliação.
-- Por isso a tabela só tem created_at.
CREATE TRIGGER conciliacoes_imutaveis
    BEFORE UPDATE OR DELETE ON contas.conciliacoes
    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao();
