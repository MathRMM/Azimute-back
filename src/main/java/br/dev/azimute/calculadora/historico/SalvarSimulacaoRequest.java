package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.Dinheiro;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Corpo do {@code PUT /api/calculadora/historico/{id}} (contrato na seção 6.5 do SPEC). */
record SalvarSimulacaoRequest(
        @NotBlank String bem,
        @NotNull LocalDate data,
        String local,
        String instituicao,
        @NotNull Dinheiro valorBem,
        @NotNull Dinheiro entrada,
        @NotNull Dinheiro parcela,
        @NotNull @Positive Integer prazo,
        @NotNull Dinheiro valorFinanciado,
        @NotNull BigDecimal taxaMensal,
        @NotNull BigDecimal taxaAnual,
        @NotNull Dinheiro totalPago,
        @NotNull Dinheiro totalJuros,
        @NotNull OrigemSimulacao origem,
        UUID ancoraId,
        String observacao,
        @NotNull OffsetDateTime calculadaEm) {

    DadosSimulacao paraDominio() {
        return new DadosSimulacao(bem, data, local, instituicao, valorBem, entrada, parcela, prazo, valorFinanciado,
                taxaMensal, taxaAnual, totalPago, totalJuros, origem,
                ancoraId == null ? null : new SimulacaoId(ancoraId), observacao, calculadaEm.toInstant());
    }
}
