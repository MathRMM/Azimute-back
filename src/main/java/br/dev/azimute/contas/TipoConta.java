package br.dev.azimute.contas;

/**
 * Onde o dinheiro está (RF02). {@code CARTEIRA} é dinheiro em espécie; {@code BENEFICIO} é VR/VA.
 * Só {@code CORRENTE} tem cheque especial (RN11).
 */
public enum TipoConta {
    CORRENTE, POUPANCA, INVESTIMENTO, CARTEIRA, BENEFICIO
}
