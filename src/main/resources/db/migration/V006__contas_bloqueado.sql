-- Contas de FGTS e de previdência (RF40 do PRD do fluxo): patrimônio que existe, mas não se gasta.
-- O consolidado as mostra à parte, como bloqueado (RF41, RN16). Cheque especial continua só em corrente
-- (constraint limite_so_em_corrente da V004).

ALTER TABLE contas.contas DROP CONSTRAINT contas_tipo_check;
ALTER TABLE contas.contas ADD CONSTRAINT contas_tipo_check CHECK (tipo IN
    ('CORRENTE', 'POUPANCA', 'INVESTIMENTO', 'CARTEIRA', 'BENEFICIO', 'FGTS', 'PREVIDENCIA'));
