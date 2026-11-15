package br.dev.azimute.contas;

/**
 * Onde o dinheiro está (RF02). {@code CARTEIRA} é dinheiro em espécie; {@code BENEFICIO} é VR/VA.
 * {@code FGTS} e {@code PREVIDENCIA} são patrimônio bloqueado: aparecem no consolidado à parte e não entram na
 * posição geral nem no saldo em conta (RF40, RF41 e RN16 do PRD do fluxo).
 * Só {@code CORRENTE} tem cheque especial (RN11).
 */
public enum TipoConta {
    CORRENTE, POUPANCA, INVESTIMENTO, CARTEIRA, BENEFICIO, FGTS, PREVIDENCIA;

    /** Dinheiro que não se movimenta livremente: só é sacado em situações previstas em lei ou no plano. */
    public boolean bloqueado() {
        return this == FGTS || this == PREVIDENCIA;
    }
}
