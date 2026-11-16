package br.dev.azimute.comum;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Scale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Taxa decimal: fração por dentro, pontos percentuais na borda, sem arredondar. */
class TaxaTest {

    @Test
    void pontos_viram_fracao_sem_perder_casas() {
        var taxa = Taxa.dePontos("75.50");
        assertThat(taxa.fracao()).isEqualByComparingTo("0.755");
        assertThat(taxa.pontos()).isEqualByComparingTo("75.50");
    }

    @Test
    void fracao_vira_pontos() {
        var taxa = Taxa.deFracao(new BigDecimal("0.0351"));
        assertThat(taxa.pontos()).isEqualByComparingTo("3.51");
        assertThat(taxa.fracao()).isEqualTo(new BigDecimal("0.0351"));
    }

    @Test
    void pontos_em_texto_aceitam_ponto_e_espacos_nas_pontas() {
        assertThat(Taxa.dePontos(" 3.51 ").fracao()).isEqualByComparingTo("0.0351");
    }

    @Test
    void zero_e_valido() {
        assertThat(Taxa.dePontos("0")).isEqualTo(Taxa.ZERO);
        assertThat(Taxa.ZERO.fracao()).isEqualByComparingTo("0");
    }

    @Test
    void nao_arredonda_mais_de_8_casas() {
        var fracao = new BigDecimal("0.0351349312345");
        assertThat(Taxa.deFracao(fracao).fracao()).isEqualTo(fracao);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3,51", "abc", "", " ", "1e", "--1"})
    void recusa_texto_que_nao_e_decimal_com_ponto(String texto) {
        assertThatThrownBy(() -> Taxa.dePontos(texto)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recusa_negativa() {
        assertThatThrownBy(() -> Taxa.dePontos("-0.01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negativa");
        assertThatThrownBy(() -> Taxa.deFracao(new BigDecimal("-0.0001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recusa_nulo() {
        assertThatThrownBy(() -> Taxa.dePontos((String) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Taxa.dePontos((BigDecimal) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Taxa.deFracao(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ordena_pela_fracao_sem_olhar_a_escala() {
        assertThat(Taxa.dePontos("1.0")).isLessThan(Taxa.dePontos("1.01"));
        assertThat(Taxa.dePontos("1.0").compareTo(Taxa.dePontos("1.00"))).isZero();
    }

    @Property
    void ida_e_volta_entre_pontos_e_fracao_nao_perde_valor(
            @ForAll @BigRange(min = "0", max = "1000") @Scale(8) BigDecimal pontos) {
        var taxa = Taxa.dePontos(pontos);
        assertThat(taxa.pontos()).isEqualByComparingTo(pontos);
        assertThat(Taxa.deFracao(taxa.fracao()).pontos()).isEqualByComparingTo(pontos);
    }
}
