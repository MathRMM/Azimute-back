package br.dev.azimute.calculadora.core;

import br.dev.azimute.comum.Dinheiro;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Propriedades que valem mais que os exemplos (RN05). Ida e volta: se passam para milhares de
 * combinações aleatórias, parcela e taxa implícita estão certas.
 */
class JurosCompostosPropriedadesTest {

    private static final BigDecimal TOLERANCIA_TAXA = new BigDecimal("0.00000001"); // 1e-8

    /**
     * Precisão total: a parcela não é arredondada a centavos, para que o erro medido seja só o do
     * algoritmo. (Com centavos, PV pequeno e prazo curto mudariam a taxa além de 1e-8 — ver SPEC, Fase 3.)
     */
    @Property(tries = 2000)
    void taxa_implicita_desfaz_a_parcela(
            @ForAll @Scale(2) @BigRange(min = "100", max = "1000000") BigDecimal pv,
            @ForAll @Scale(6) @BigRange(min = "0.0001", max = "0.25") BigDecimal i,
            @ForAll @IntRange(min = 1, max = 120) int n) {

        var pmtExato = JurosCompostos.parcelaExata(pv, i, n);
        var taxa = JurosCompostos.taxaImplicitaExata(pv, pmtExato, n);

        assertThat(taxa).isCloseTo(i, within(TOLERANCIA_TAXA));
    }

    /**
     * Na API pública, com centavos: a taxa encontrada a partir da parcela arredondada reproduz
     * exatamente a mesma parcela arredondada.
     */
    @Property(tries = 2000)
    void taxa_implicita_reproduz_a_parcela_em_centavos(
            @ForAll @Scale(2) @BigRange(min = "100", max = "1000000") BigDecimal pv,
            @ForAll @Scale(6) @BigRange(min = "0.0001", max = "0.25") BigDecimal i,
            @ForAll @IntRange(min = 1, max = 120) int n) {

        var valor = new Dinheiro(pv);
        var parcela = JurosCompostos.parcela(valor, new TaxaMensal(i), n);
        // Com taxa ínfima e parcela arredondada para baixo, o total pode não superar o financiado.
        Assume.that(parcela.vezes(n).compareTo(valor) > 0);

        var taxa = JurosCompostos.taxaImplicita(valor, parcela, n);

        assertThat(JurosCompostos.parcela(valor, taxa, n)).isEqualTo(parcela);
    }

    @Property(tries = 1000)
    void valor_presente_desfaz_a_parcela(
            @ForAll @Scale(2) @BigRange(min = "100", max = "1000000") BigDecimal pv,
            @ForAll @Scale(6) @BigRange(min = "0", max = "0.25") BigDecimal i,
            @ForAll @IntRange(min = 1, max = 120) int n) {

        var taxa = new TaxaMensal(i);
        var parcela = JurosCompostos.parcela(new Dinheiro(pv), taxa, n);
        var vp = JurosCompostos.valorPresente(parcela, taxa, n);

        // cada parcela arredondada carrega até meio centavo de erro
        assertThat(vp.valor()).isCloseTo(pv, within(new BigDecimal("0.005").multiply(BigDecimal.valueOf(n))));
    }
}
