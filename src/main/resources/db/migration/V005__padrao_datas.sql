-- Padrão de datas nas tabelas das fases 1–2 (PRD de contas, RN14 e RF40–RF43; SPEC de contas, Fase 6).
-- created_at em todas; updated_at onde o registro muda; deleted_at onde há exclusão lógica.
-- updated_at é preenchido pelo SQL da aplicação, não por trigger (decisão 14 da doc 00).
-- As migrations anteriores não são editadas: o Flyway confere o checksum de cada uma.

-- Simulações: sincronizada_em já era o instante em que o servidor gravou, então vira created_at.
-- A API continua expondo o campo como sincronizadaEm.
ALTER TABLE calculadora.simulacoes RENAME COLUMN sincronizada_em TO created_at;
ALTER TABLE calculadora.simulacoes
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN deleted_at TIMESTAMPTZ;
UPDATE calculadora.simulacoes SET updated_at = created_at;

-- Índices de leitura só sobre as simulações vivas (a exclusão passa a ser lógica, RF41).
DROP INDEX calculadora.simulacoes_bem_taxa_idx;
DROP INDEX calculadora.simulacoes_data_idx;
CREATE INDEX simulacoes_bem_taxa_idx ON calculadora.simulacoes (bem, taxa_mensal) WHERE deleted_at IS NULL;
CREATE INDEX simulacoes_data_idx ON calculadora.simulacoes (data DESC) WHERE deleted_at IS NULL;

-- O trigger simulacao_valores_imutaveis compara só as colunas de valor: updated_at e deleted_at podem mudar.

-- Usuário: único, nunca excluído.
ALTER TABLE auth.usuarios RENAME COLUMN criado_em TO created_at;
ALTER TABLE auth.usuarios ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE auth.usuarios SET updated_at = created_at;

-- Dispositivo: a revogação continua definitiva (revogado_em), por segurança; sem deleted_at.
ALTER TABLE auth.dispositivos RENAME COLUMN criado_em TO created_at;
ALTER TABLE auth.dispositivos ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE auth.dispositivos SET updated_at = created_at;

-- Tentativas de login: log, só inserção. Os índices acompanham a coluna renomeada.
ALTER TABLE auth.tentativas_login RENAME COLUMN em TO created_at;
