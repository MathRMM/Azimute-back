package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.comum.Fuso;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Simulação como a API devolve. Dinheiro sai como string pelo módulo do kernel; as taxas, como fração em
 * string com 8 casas (decisão T2). {@code taxaMensal} é a "taxa efetiva das parcelas", nunca CET (RN08).
 */
record SimulacaoResponse(
        UUID id,
        String bem,
        LocalDate data,
        String local,
        String instituicao,
        Dinheiro valorBem,
        Dinheiro entrada,
        Dinheiro parcela,
        int prazo,
        Dinheiro valorFinanciado,
        String taxaMensal,
        String taxaAnual,
        Dinheiro totalPago,
        Dinheiro totalJuros,
        OrigemSimulacao origem,
        UUID ancoraId,
        String observacao,
        StatusSimulacao status,
        OffsetDateTime calculadaEm,
        OffsetDateTime sincronizadaEm) {

    static SimulacaoResponse de(SimulacaoSalva s) {
        var d = s.dados();
        return new SimulacaoResponse(
                s.id().valor(), d.bem(), d.data(), d.local(), d.instituicao(),
                d.valorBem(), d.entrada(), d.parcela(), d.prazo(), d.valorFinanciado(),
                d.taxaMensal().toPlainString(), d.taxaAnual().toPlainString(),
                d.totalPago(), d.totalJuros(), d.origem(),
                d.ancoraId() == null ? null : d.ancoraId().valor(),
                d.observacao(), s.status(),
                Fuso.naApi(d.calculadaEm()),
                Fuso.naApi(s.sincronizadaEm()));
    }
}
