package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;
import java.util.List;

/**
 * Extrato de uma conta num período (RF12). {@code saldoInicial} é o saldo no fim do dia anterior a {@code de};
 * {@code saldoFinal}, no fim do dia {@code ate}. As conciliações do período aparecem como marcos: depois de uma
 * conciliação, o saldo parte do valor real, então a soma das linhas nem sempre explica a diferença entre os dois.
 */
record Extrato(ContaId conta, LocalDate de, LocalDate ate, Dinheiro saldoInicial, Dinheiro saldoFinal,
               List<Movimentacao> linhas, List<Conciliacao> conciliacoes) {}
