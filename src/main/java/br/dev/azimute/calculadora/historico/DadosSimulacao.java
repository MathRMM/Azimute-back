package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.Dinheiro;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * O que o cliente envia ao salvar: entrada informada e saída calculada no aparelho (snapshot).
 * <p>
 * Normaliza o que o banco normalizaria, para que um reenvio idêntico seja reconhecido como idêntico:
 * taxas com 8 casas ({@code NUMERIC(12,8)}), instante em microssegundos ({@code TIMESTAMPTZ}), textos sem
 * espaços nas pontas.
 */
public record DadosSimulacao(
        String bem,
        LocalDate data,
        String local,
        String instituicao,
        Dinheiro valorBem,
        Dinheiro entrada,
        Dinheiro parcela,
        int prazo,
        Dinheiro valorFinanciado,
        BigDecimal taxaMensal,
        BigDecimal taxaAnual,
        Dinheiro totalPago,
        Dinheiro totalJuros,
        OrigemSimulacao origem,
        SimulacaoId ancoraId,
        String observacao,
        Instant calculadaEm) {

    public static final int ESCALA_TAXA = 8;

    public DadosSimulacao {
        bem = Objects.requireNonNull(bem, "bem").strip();
        local = vazioComoNulo(local);
        instituicao = vazioComoNulo(instituicao);
        observacao = vazioComoNulo(observacao);
        taxaMensal = taxaMensal.setScale(ESCALA_TAXA, RoundingMode.HALF_EVEN);
        taxaAnual = taxaAnual.setScale(ESCALA_TAXA, RoundingMode.HALF_EVEN);
        calculadaEm = calculadaEm.truncatedTo(ChronoUnit.MICROS);
    }

    /** Mesmos valores, ignorando a observação (campo anotativo, decisão D2). */
    boolean mesmoConteudo(DadosSimulacao outro) {
        return this.semObservacao().equals(outro.semObservacao());
    }

    private DadosSimulacao semObservacao() {
        return new DadosSimulacao(bem, data, local, instituicao, valorBem, entrada, parcela, prazo, valorFinanciado,
                taxaMensal, taxaAnual, totalPago, totalJuros, origem, ancoraId, null, calculadaEm);
    }

    private static String vazioComoNulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }
}
