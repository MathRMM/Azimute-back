package br.dev.azimute.calculadora.core;

import br.dev.azimute.calculadora.core.VetoresDeOuro.VetorComparacao;
import br.dev.azimute.comum.Dinheiro;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** RF15, decisão D7: financiar e deixar o dinheiro rendendo — saio na frente ou atrás? */
class ComparadorTest {

    private static final TaxaMensal CDB = new TaxaMensal(new BigDecimal("0.008"));

    static List<VetorComparacao> vetores() {
        return VetoresDeOuro.comparacao();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vetores")
    void reproduz_os_vetores_de_comparacao(VetorComparacao v) {
        var veredito = Comparador.compararAVistaVsFinanciado(
                Dinheiro.de(v.precoAVista()), Dinheiro.de(v.entrada()), Dinheiro.de(v.parcela()),
                v.prazo(), new TaxaMensal(new BigDecimal(v.rendimento())));

        assertThat(veredito.custoTotalFinanciado()).isEqualTo(Dinheiro.de(v.custoTotalFinanciado()));
        assertThat(veredito.custoTotalAVista()).isEqualTo(Dinheiro.de(v.custoTotalAVista()));
        assertThat(veredito.rendimentoDoDinheiroGuardado()).isEqualTo(Dinheiro.de(v.rendimentoDoDinheiroGuardado()));
        assertThat(veredito.delta()).isEqualTo(Dinheiro.de(v.delta()));
        assertThat(veredito.taxaFinanciamento().fracao())
                .isCloseTo(new BigDecimal(v.taxaFinanciamento()), within(new BigDecimal(v.toleranciaTaxa())));
        assertThat(veredito.taxaRendimento().fracao()).isEqualByComparingTo(v.rendimento());
    }

    @Test
    void financiamento_caro_contra_cdb_a_vista_vence() {
        var v = Comparador.compararAVistaVsFinanciado(
                Dinheiro.de("13990.00"), Dinheiro.ZERO, Dinheiro.de("930.42"), 24, CDB);
        assertThat(v.delta().positivo()).isTrue();
    }

    @Test
    void taxa_promocional_abaixo_do_rendimento_financiar_vence() {
        var parcela = JurosCompostos.parcela(Dinheiro.de("13990.00"), TaxaMensal.dePercentual("0.6"), 24);
        var v = Comparador.compararAVistaVsFinanciado(Dinheiro.de("13990.00"), Dinheiro.ZERO, parcela, 24, CDB);
        assertThat(v.delta().negativo()).isTrue();
    }

    @Test
    void financiamento_sem_juros_tem_taxa_zero_e_nao_lanca() {
        var v = Comparador.compararAVistaVsFinanciado(
                Dinheiro.de("1200.00"), Dinheiro.ZERO, Dinheiro.de("100.00"), 12, CDB);
        assertThat(v.taxaFinanciamento()).isEqualTo(TaxaMensal.ZERO);
        assertThat(v.delta().negativo()).isTrue();
    }

    @Test
    void rendimento_zero_reduz_a_comparacao_aos_juros_pagos() {
        var v = Comparador.compararAVistaVsFinanciado(
                Dinheiro.de("13990.00"), Dinheiro.ZERO, Dinheiro.de("930.42"), 24, TaxaMensal.ZERO);
        assertThat(v.rendimentoDoDinheiroGuardado()).isEqualTo(Dinheiro.ZERO);
        assertThat(v.delta()).isEqualTo(Dinheiro.de("8340.08"));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, 100, 12",          // preço zero
            "1000, -1, 100, 12",      // entrada negativa
            "1000, 1000, 100, 12",    // entrada cobre o bem: não há o que financiar
            "1000, 0, 0, 12",         // parcela zero
            "1000, 0, 100, 0"         // prazo zero
    })
    void entradas_invalidas_sao_rejeitadas(String preco, String entrada, String parcela, int prazo) {
        assertThatThrownBy(() -> Comparador.compararAVistaVsFinanciado(
                Dinheiro.de(preco), Dinheiro.de(entrada), Dinheiro.de(parcela), prazo, CDB))
                .isInstanceOf(ValorInvalidoException.class);
    }

    @Property(tries = 500)
    void delta_e_sempre_financiado_menos_rendimento_menos_a_vista(
            @ForAll @Scale(2) @BigRange(min = "1000", max = "200000") BigDecimal preco,
            @ForAll @Scale(2) @BigRange(min = "0", max = "0.9") BigDecimal fracaoEntrada,
            @ForAll @Scale(4) @BigRange(min = "0.001", max = "0.08") BigDecimal taxa,
            @ForAll @Scale(4) @BigRange(min = "0", max = "0.02") BigDecimal rendimento,
            @ForAll @IntRange(min = 1, max = 72) int prazo) {

        var precoAVista = new Dinheiro(preco);
        var entrada = new Dinheiro(preco.multiply(fracaoEntrada));
        var parcela = JurosCompostos.parcela(precoAVista.menos(entrada), new TaxaMensal(taxa), prazo);

        var v = Comparador.compararAVistaVsFinanciado(precoAVista, entrada, parcela, prazo, new TaxaMensal(rendimento));

        assertThat(v.delta())
                .isEqualTo(v.custoTotalFinanciado().menos(v.rendimentoDoDinheiroGuardado()).menos(v.custoTotalAVista()));
    }
}
