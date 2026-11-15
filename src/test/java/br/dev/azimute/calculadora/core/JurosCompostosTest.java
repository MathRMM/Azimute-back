package br.dev.azimute.calculadora.core;

import br.dev.azimute.calculadora.core.VetoresDeOuro.VetorTaxa;
import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** RF11–RF14, RF17, RN04–RN07, RNF07. */
class JurosCompostosTest {

    static List<VetorTaxa> vetores() {
        return VetoresDeOuro.taxaImplicita();
    }

    // ---- vetores de ouro ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("vetores")
    void taxa_implicita_reproduz_os_vetores_de_ouro(VetorTaxa v) {
        var taxa = JurosCompostos.taxaImplicita(Dinheiro.de(v.pv()), Dinheiro.de(v.pmt()), v.n());
        assertThat(taxa.fracao()).isCloseTo(v.taxa(), within(v.tolerancia()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vetores")
    void taxa_anual_arredondada_bate_com_o_vetor(VetorTaxa v) {
        var taxa = JurosCompostos.taxaImplicita(Dinheiro.de(v.pv()), Dinheiro.de(v.pmt()), v.n());
        assertThat(taxa.anualizada().percentual().setScale(1, RoundingMode.HALF_UP))
                .isEqualByComparingTo(v.taxaAnualPercentual1Casa());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vetores")
    void parcela_com_a_taxa_do_vetor_reproduz_o_pmt(VetorTaxa v) {
        var parcela = JurosCompostos.parcela(Dinheiro.de(v.pv()), new TaxaMensal(v.taxa()), v.n());
        assertThat(parcela.valor()).isCloseTo(new BigDecimal(v.pmt()), within(new BigDecimal("0.01")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vetores")
    void totais_do_vetor_sao_coerentes(VetorTaxa v) {
        var totalPago = Dinheiro.de(v.pmt()).vezes(v.n());
        assertThat(totalPago).isEqualTo(Dinheiro.de(v.totalPago()));
        assertThat(totalPago.menos(Dinheiro.de(v.pv()))).isEqualTo(Dinheiro.de(v.totalJuros()));
    }

    // ---- operações diretas ----

    @Test
    void montante_compoe_juros() {
        // 1000 × 1,01^12 = 1126,825…
        var m = JurosCompostos.montante(Dinheiro.de("1000"), TaxaMensal.dePercentual("1"), 12);
        assertThat(m).isEqualTo(Dinheiro.de("1126.83"));
    }

    @Test
    void montante_sem_prazo_e_o_proprio_valor() {
        assertThat(JurosCompostos.montante(Dinheiro.de("1000"), TaxaMensal.dePercentual("1"), 0))
                .isEqualTo(Dinheiro.de("1000"));
    }

    @Test
    void parcela_price_conhecida() {
        // 10.000 a 2% em 12x → 945,596…
        assertThat(JurosCompostos.parcela(Dinheiro.de("10000"), TaxaMensal.dePercentual("2"), 12))
                .isEqualTo(Dinheiro.de("945.60"));
    }

    @Test
    void parcela_sem_juros_divide_o_valor() {
        assertThat(JurosCompostos.parcela(Dinheiro.de("1000"), TaxaMensal.ZERO, 3)).isEqualTo(Dinheiro.de("333.33"));
    }

    @Test
    void valor_presente_desconta_as_parcelas() {
        // inverso da parcela_price_conhecida: 945,60 × a(12; 2%) ≈ 10.000,04
        var vp = JurosCompostos.valorPresente(Dinheiro.de("945.60"), TaxaMensal.dePercentual("2"), 12);
        assertThat(vp.valor()).isCloseTo(new BigDecimal("10000.00"), within(new BigDecimal("0.05")));
    }

    @Test
    void valor_presente_sem_juros_e_a_soma() {
        assertThat(JurosCompostos.valorPresente(Dinheiro.de("100"), TaxaMensal.ZERO, 12))
                .isEqualTo(Dinheiro.de("1200"));
    }

    // ---- bordas ----

    @Test
    void prazo_unitario_e_exato() {
        var taxa = JurosCompostos.taxaImplicita(Dinheiro.de("1000.00"), Dinheiro.de("1010.00"), 1);
        assertThat(taxa.fracao()).isEqualByComparingTo("0.01");
    }

    @Test
    void total_igual_ao_financiado_nao_tem_juros() {
        assertThatThrownBy(() -> JurosCompostos.taxaImplicita(Dinheiro.de("1200"), Dinheiro.de("100"), 12))
                .isInstanceOf(SemJurosException.class)
                .satisfies(e -> assertThat(((SemJurosException) e).codigo()).isEqualTo("SEM_JUROS"));
    }

    @Test
    void total_menor_que_o_financiado_nao_tem_juros() {
        assertThatThrownBy(() -> JurosCompostos.taxaImplicita(Dinheiro.de("1200"), Dinheiro.de("99"), 12))
                .isInstanceOf(SemJurosException.class);
    }

    @ParameterizedTest
    @CsvSource({"0, 100, 12", "-1, 100, 12", "1000, 0, 12", "1000, -5, 12", "1000, 100, 0", "1000, 100, -1"})
    void entradas_degeneradas_sao_rejeitadas(String pv, String pmt, int n) {
        assertThatThrownBy(() -> JurosCompostos.taxaImplicita(Dinheiro.de(pv), Dinheiro.de(pmt), n))
                .isInstanceOf(ValorInvalidoException.class);
    }

    @Test
    void taxa_alta_dentro_da_faixa_converge() {
        // ≈ 9,7% a.m.
        var taxa = JurosCompostos.taxaImplicita(Dinheiro.de("1000"), Dinheiro.de("400"), 3);
        assertThat(taxa.fracao()).isBetween(new BigDecimal("0.096"), new BigDecimal("0.098"));
        assertThat(JurosCompostos.parcela(Dinheiro.de("1000"), taxa, 3)).isEqualTo(Dinheiro.de("400"));
    }

    @Test
    void taxa_acima_de_30_por_cento_e_rejeitada() {
        assertThatThrownBy(() -> JurosCompostos.taxaImplicita(Dinheiro.de("1000"), Dinheiro.de("600"), 3))
                .isInstanceOf(TaxaForaDaFaixaException.class)
                .satisfies(e -> assertThat(((TaxaForaDaFaixaException) e).codigo()).isEqualTo("TAXA_FORA_DA_FAIXA"));
    }

    @Test
    void taxa_abaixo_da_faixa_e_rejeitada() {
        // total supera o financiado em centavos sobre 1 milhão: taxa ≈ 1e-8, abaixo de 1e-6
        assertThatThrownBy(() -> JurosCompostos.taxaImplicita(Dinheiro.de("1000000"), Dinheiro.de("500000.01"), 2))
                .isInstanceOf(TaxaForaDaFaixaException.class);
        assertThatThrownBy(() -> JurosCompostos.taxaImplicita(Dinheiro.de("1000000"), Dinheiro.de("1000000.01"), 1))
                .isInstanceOf(TaxaForaDaFaixaException.class);
    }

    @Test
    void prazo_unitario_respeita_a_mesma_faixa() {
        assertThatThrownBy(() -> JurosCompostos.taxaImplicita(Dinheiro.de("1000"), Dinheiro.de("1400"), 1))
                .isInstanceOf(TaxaForaDaFaixaException.class);
    }

    // ---- desempenho ----

    @Test
    void taxa_implicita_e_rapida() {
        var pv = Dinheiro.de("17430.00");
        var pmt = Dinheiro.de("860.69");
        for (int i = 0; i < 200; i++) {
            JurosCompostos.taxaImplicita(pv, pmt, 36);
        }
        int rodadas = 50;
        long inicio = System.nanoTime();
        for (int i = 0; i < rodadas; i++) {
            JurosCompostos.taxaImplicita(pv, pmt, 36);
        }
        double msPorChamada = (System.nanoTime() - inicio) / 1_000_000.0 / rodadas;
        assertThat(msPorChamada).isLessThan(10.0);
    }
}
