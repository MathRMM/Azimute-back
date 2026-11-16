-- Módulo fluxo, parte 1, com holerite (PRD do fluxo, SPEC do fluxo §6.1): categorias, gastos, ganhos, recebimentos
-- e a folha de pagamento.
-- Datas de controle (RNF08): created_at em todas; updated_at onde o registro muda; deleted_at onde há exclusão lógica.
-- updated_at é preenchido pelo SQL da aplicação, não por trigger (decisão 14 da doc 00).

CREATE SCHEMA fluxo;

-- Sem deleted_at: categoria não é excluída, é desativada (gastos antigos continuam apontando para ela, RF04).
CREATE TABLE fluxo.categorias (
    id          UUID PRIMARY KEY DEFAULT uuidv7(),
    nome        TEXT NOT NULL CHECK (btrim(nome) <> ''),
    predefinida BOOLEAN NOT NULL DEFAULT false,
    ativa       BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Nome único ignorando maiúsculas e espaços nas pontas (RN06).
CREATE UNIQUE INDEX categorias_nome_unico ON fluxo.categorias (lower(btrim(nome)));

-- Categorias pré-definidas (RF01).
INSERT INTO fluxo.categorias (nome, predefinida) VALUES
    ('Alimentação', true), ('Moradia', true), ('Transporte', true), ('Saúde', true), ('Educação', true),
    ('Lazer', true), ('Assinaturas', true), ('Vestuário', true), ('Outros', true);

CREATE TABLE fluxo.holerites (
    id                        UUID PRIMARY KEY DEFAULT uuidv7(),
    competencia               DATE NOT NULL CHECK (EXTRACT(DAY FROM competencia) = 1),
    origem                    TEXT NOT NULL CHECK (origem IN ('PDF', 'MANUAL')),
    -- SHA-256 do PDF ou do JSON canônico do lançamento manual: detecta o reenvio (RN11, T9).
    hash                      TEXT NOT NULL,
    total_vencimentos         NUMERIC(19,2) NOT NULL CHECK (total_vencimentos >= 0),
    total_descontos           NUMERIC(19,2) NOT NULL CHECK (total_descontos >= 0),
    liquido                   NUMERIC(19,2) NOT NULL CHECK (liquido >= 0),
    salario_base              NUMERIC(19,2),
    base_inss                 NUMERIC(19,2),
    base_fgts                 NUMERIC(19,2),
    fgts_mes                  NUMERIC(19,2),
    base_irrf                 NUMERIC(19,2),
    -- Gravada na importação pela regra vigente na competência; só muda por correção explícita (RN19, T7).
    contrapartida_previdencia NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (contrapartida_previdencia >= 0),
    data_adiantamento         DATE,
    data_liquido              DATE NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at                TIMESTAMPTZ,

    -- Líquido = vencimentos − descontos (RN02, RN10): o líquido nunca supera o bruto.
    CONSTRAINT totais_coerentes CHECK (total_vencimentos - total_descontos = liquido)
);

-- Um holerite vivo por competência; excluído não impede reimportar (T9).
CREATE UNIQUE INDEX holerites_competencia_unica ON fluxo.holerites (competencia) WHERE deleted_at IS NULL;

-- O PDF fica à parte para a listagem não carregar o arquivo (T8).
CREATE TABLE fluxo.holerite_arquivos (
    holerite_id UUID PRIMARY KEY REFERENCES fluxo.holerites (id),
    conteudo    BYTEA NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Rubricas exatamente como vieram no holerite: a interpretação vem do catálogo, na leitura (T10).
CREATE TABLE fluxo.holerite_rubricas (
    holerite_id UUID NOT NULL REFERENCES fluxo.holerites (id),
    ordem       INT NOT NULL,
    codigo      TEXT NOT NULL CHECK (btrim(codigo) <> ''),
    descricao   TEXT NOT NULL,
    referencia  NUMERIC(19,2),
    vencimento  NUMERIC(19,2) CHECK (vencimento >= 0),
    desconto    NUMERIC(19,2) CHECK (desconto >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (holerite_id, ordem),
    CONSTRAINT vencimento_ou_desconto CHECK ((vencimento IS NULL) <> (desconto IS NULL))
);

CREATE TABLE fluxo.rubricas_catalogo (
    codigo        TEXT PRIMARY KEY,
    descricao     TEXT NOT NULL,
    natureza      TEXT NOT NULL CHECK (natureza IN ('PROVENTO', 'INSS', 'IRRF', 'PREVIDENCIA_BASICA',
                                                    'PREVIDENCIA_VOLUNTARIA', 'ADIANTAMENTO', 'GASTO',
                                                    'OUTRO_DESCONTO')),
    categoria_id  UUID REFERENCES fluxo.categorias (id),
    -- Rubrica nova entra com natureza provisória e o aviso sai na resposta da importação (RF36, T11).
    a_classificar BOOLEAN NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Só a rubrica que vira gasto tem categoria (RF35, RN12).
    CONSTRAINT categoria_so_em_gasto CHECK ((natureza = 'GASTO') = (categoria_id IS NOT NULL))
);

-- As seis rubricas do exemplo, já classificadas (RF35).
INSERT INTO fluxo.rubricas_catalogo (codigo, descricao, natureza, categoria_id) VALUES
    ('0001', 'Salário Mensal', 'PROVENTO', NULL),
    ('/314', 'INSS', 'INSS', NULL),
    ('/507', 'Vale-Transporte', 'GASTO', (SELECT id FROM fluxo.categorias WHERE nome = 'Transporte')),
    ('2363', 'Prev Priv Bas Func', 'PREVIDENCIA_BASICA', NULL),
    ('2364', 'Prev Priv Vol Func', 'PREVIDENCIA_VOLUNTARIA', NULL),
    ('/B02', 'Decto Adto Salário', 'ADIANTAMENTO', NULL);

-- Regras de contrapartida com vigência: só inserção. Regra nova vale dali em diante e não muda o passado (RN19, T7).
CREATE TABLE fluxo.regras_contrapartida (
    a_partir_de          DATE PRIMARY KEY CHECK (EXTRACT(DAY FROM a_partir_de) = 1),
    percentual_da_basica NUMERIC(7,4) NOT NULL CHECK (percentual_da_basica >= 0),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER regras_contrapartida_imutaveis
    BEFORE UPDATE OR DELETE ON fluxo.regras_contrapartida
    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao();

-- Regra inicial: a empresa põe o mesmo valor da básica (RF35a).
INSERT INTO fluxo.regras_contrapartida (a_partir_de, percentual_da_basica) VALUES (DATE '2000-01-01', 1.0000);

-- Linha única: as contas para onde vão o líquido, o FGTS e a previdência.
CREATE TABLE fluxo.configuracao_folha (
    id                BOOLEAN PRIMARY KEY DEFAULT true CHECK (id),
    conta_salario     UUID NOT NULL REFERENCES contas.contas (id),
    conta_fgts        UUID NOT NULL REFERENCES contas.contas (id),
    conta_previdencia UUID NOT NULL REFERENCES contas.contas (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE fluxo.ganhos (
    -- Vem do cliente no PUT avulso (idempotência); do servidor quando o ganho nasce do holerite.
    id          UUID PRIMARY KEY,
    competencia DATE NOT NULL CHECK (EXTRACT(DAY FROM competencia) = 1),
    descricao   TEXT NOT NULL CHECK (btrim(descricao) <> ''),
    tipo        TEXT NOT NULL CHECK (tipo IN ('SALARIO', 'PLR', 'FREELA', 'REEMBOLSO', 'OUTRO')),
    bruto       NUMERIC(19,2) NOT NULL CHECK (bruto >= 0),
    liquido     NUMERIC(19,2) NOT NULL CHECK (liquido >= 0),
    holerite_id UUID REFERENCES fluxo.holerites (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,

    CONSTRAINT liquido_nao_supera_bruto CHECK (liquido <= bruto),
    -- Salário só vem do holerite; os outros tipos, só da API avulsa (RF21).
    CONSTRAINT salario_vem_do_holerite CHECK ((tipo = 'SALARIO') = (holerite_id IS NOT NULL))
);

CREATE TABLE fluxo.recebimentos (
    -- É a referência da ENTRADA em contas (T3, T5, T6).
    id            UUID PRIMARY KEY,
    -- Nulo: adiantamento lançado antes do holerite, ainda solto (RF34).
    ganho_id      UUID REFERENCES fluxo.ganhos (id),
    competencia   DATE NOT NULL CHECK (EXTRACT(DAY FROM competencia) = 1),
    papel         TEXT NOT NULL CHECK (papel IN ('AVULSO', 'ADIANTAMENTO', 'LIQUIDO')),
    valor         NUMERIC(19,2) NOT NULL CHECK (valor > 0),
    data          DATE NOT NULL,
    conta_id      UUID NOT NULL REFERENCES contas.contas (id),
    lancado_antes BOOLEAN NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);

-- FGTS e previdência do mês: cada um vira uma ENTRADA na conta configurada.
CREATE TABLE fluxo.lancamentos_folha (
    -- Referência determinística, derivada do holerite e do papel (T6).
    id          UUID PRIMARY KEY,
    holerite_id UUID NOT NULL REFERENCES fluxo.holerites (id),
    papel       TEXT NOT NULL CHECK (papel IN ('FGTS', 'PREV_BASICA', 'PREV_CONTRAPARTIDA', 'PREV_VOLUNTARIA')),
    valor       NUMERIC(19,2) NOT NULL CHECK (valor > 0),
    data        DATE NOT NULL,
    conta_id    UUID NOT NULL REFERENCES contas.contas (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE TABLE fluxo.gastos (
    id           UUID PRIMARY KEY,
    data         DATE NOT NULL,
    descricao    TEXT NOT NULL CHECK (btrim(descricao) <> ''),
    valor        NUMERIC(19,2) NOT NULL CHECK (valor > 0),
    categoria_id UUID NOT NULL REFERENCES fluxo.categorias (id),
    forma        TEXT NOT NULL CHECK (forma IN ('A_VISTA', 'DESCONTO_EM_FOLHA')),
    conta_id     UUID REFERENCES contas.contas (id),
    holerite_id  UUID REFERENCES fluxo.holerites (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ,

    -- À vista sai de uma conta; em folha nem chegou à conta, vem do holerite (RF14).
    CONSTRAINT forma_coerente CHECK (
        (forma = 'A_VISTA'           AND conta_id IS NOT NULL AND holerite_id IS NULL) OR
        (forma = 'DESCONTO_EM_FOLHA' AND holerite_id IS NOT NULL AND conta_id IS NULL))
);

-- Índices de leitura só sobre os registros vivos.
CREATE INDEX gastos_data_idx ON fluxo.gastos (data DESC) WHERE deleted_at IS NULL;
CREATE INDEX gastos_categoria_idx ON fluxo.gastos (categoria_id) WHERE deleted_at IS NULL;
CREATE INDEX ganhos_competencia_idx ON fluxo.ganhos (competencia DESC) WHERE deleted_at IS NULL;
CREATE INDEX recebimentos_ganho_idx ON fluxo.recebimentos (ganho_id) WHERE deleted_at IS NULL;
CREATE INDEX lancamentos_folha_holerite_idx ON fluxo.lancamentos_folha (holerite_id) WHERE deleted_at IS NULL;
