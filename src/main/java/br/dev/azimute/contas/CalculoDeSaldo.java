package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;

/**
 * A única regra de saldo do módulo, sem banco (RN06, RN11). Quem chama entrega a base (última conciliação) e a
 * soma, já com sinal, das movimentações com data posterior a ela.
 */
final class CalculoDeSaldo {

    private CalculoDeSaldo() {}

    static Saldo calcular(Conta conta, Conciliacao base, Dinheiro efeitoDesdeABase, int movimentacoesDesdeABase) {
        var atual = base.saldoReal().mais(efeitoDesdeABase);
        return new Saldo(atual, base.data(), base.saldoReal(), movimentacoesDesdeABase,
                conta.tipo() == TipoConta.CORRENTE ? chequeEspecial(conta.limiteChequeEspecial(), atual) : null);
    }

    /**
     * Saldo negativo em corrente é uso do cheque especial, até o limite. O que passar do limite continua sendo
     * saldo negativo, só que marcado (P8). Não entra em nenhuma soma: o saldo negativo já está na posição geral.
     */
    private static Saldo.ChequeEspecial chequeEspecial(Dinheiro limite, Dinheiro atual) {
        var negativo = atual.negativo() ? Dinheiro.ZERO.menos(atual) : Dinheiro.ZERO;
        var emUso = negativo.compareTo(limite) > 0 ? limite : negativo;
        return new Saldo.ChequeEspecial(limite, emUso, limite.menos(emUso), negativo.compareTo(limite) > 0);
    }
}
