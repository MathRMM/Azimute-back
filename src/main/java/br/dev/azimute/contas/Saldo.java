package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;

/**
 * Saldo atual de uma conta (RF05): a última conciliação (a base) mais o efeito das movimentações posteriores
 * (RN06). Pode ser negativo (RN11). {@code chequeEspecial} só existe em conta corrente; nas outras é nulo.
 */
record Saldo(Dinheiro atual, LocalDate baseEm, Dinheiro baseValor, int movimentacoesDesdeABase,
             ChequeEspecial chequeEspecial) {

    /**
     * Uso do limite quando a corrente está negativa (RN11). {@code acimaDoLimite} marca o negativo que passou do
     * limite, sem bloquear nada (P8).
     */
    record ChequeEspecial(Dinheiro limite, Dinheiro emUso, Dinheiro disponivel, boolean acimaDoLimite) {}
}
