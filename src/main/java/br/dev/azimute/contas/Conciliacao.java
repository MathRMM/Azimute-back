package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;

/**
 * Marco zero móvel do saldo (RF20). A divergência é fato do momento da conciliação: é gravada e nunca
 * recalculada (RN07). O saldo real vale para o fim do dia {@code data} (P2).
 */
record Conciliacao(ConciliacaoId id, ContaId conta, LocalDate data, Dinheiro saldoReal, Dinheiro saldoCalculado,
                   Dinheiro divergencia, String justificativa) {
}
