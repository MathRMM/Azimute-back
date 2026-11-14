package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;
import java.util.UUID;

/** Conta com o saldo atual. Dinheiro sai como string decimal (T2 do SPEC da fundação). */
record ContaResponse(UUID id, String apelido, String instituicao, TipoConta tipo, Funcao funcao,
                     Dinheiro limiteChequeEspecial, boolean ativa, SaldoResponse saldo) {

    static ContaResponse de(ContaComSaldo item) {
        var conta = item.conta();
        return new ContaResponse(conta.id().valor(), conta.apelido(), conta.instituicao(), conta.tipo(),
                conta.funcao(), conta.limiteChequeEspecial(), conta.ativa(), SaldoResponse.de(item.saldo()));
    }

    /** {@code chequeEspecial} é nulo fora de conta corrente. */
    record SaldoResponse(Dinheiro atual, UltimaConciliacao ultimaConciliacao, int movimentacoesDesdeABase,
                         Saldo.ChequeEspecial chequeEspecial) {

        static SaldoResponse de(Saldo saldo) {
            return new SaldoResponse(saldo.atual(), new UltimaConciliacao(saldo.baseEm(), saldo.baseValor()),
                    saldo.movimentacoesDesdeABase(), saldo.chequeEspecial());
        }
    }

    record UltimaConciliacao(LocalDate data, Dinheiro saldoReal) {}
}
