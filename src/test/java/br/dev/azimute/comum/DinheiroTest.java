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

/** RF01, RN01. */
class DinheiroTest {

    @Test
    void arredonda_meio_para_o_par_mais_proximo() {
        assertThat(Dinheiro.de("0.125").valor()).isEqualByComparingTo("0.12");
        assertThat(Dinheiro.de("0.135").valor()).isEqualByComparingTo("0.14");
        assertThat(Dinheiro.de("2.5").valor().scale()).isEqualTo(2);
    }

    @Test
    void soma_decimal_e_exata() {
        assertThat(Dinheiro.de("0.10").mais(Dinheiro.de("0.20"))).isEqualTo(Dinheiro.de("0.30"));
    }

    @Test
    void subtracao_pode_produzir_negativo() {
        var resultado = Dinheiro.de("10.00").menos(Dinheiro.de("10.01"));
        assertThat(resultado).isEqualTo(Dinheiro.de("-0.01"));
        assertThat(resultado.negativo()).isTrue();
        assertThat(resultado.positivo()).isFalse();
    }

    @Test
    void zero_nao_e_negativo_nem_positivo() {
        assertThat(Dinheiro.ZERO.negativo()).isFalse();
        assertThat(Dinheiro.ZERO.positivo()).isFalse();
        assertThat(Dinheiro.ZERO).isEqualTo(Dinheiro.de("0.00"));
    }

    @Test
    void multiplica_por_inteiro() {
        assertThat(Dinheiro.de("860.69").vezes(36)).isEqualTo(Dinheiro.de("30984.84"));
    }

    @Test
    void igualdade_independe_da_escala_de_entrada() {
        assertThat(Dinheiro.de("5")).isEqualTo(Dinheiro.de("5.00"));
        assertThat(Dinheiro.de("5").hashCode()).isEqualTo(Dinheiro.de("5.00").hashCode());
    }

    @Test
    void ordena_por_valor() {
        assertThat(Dinheiro.de("1.00")).isLessThan(Dinheiro.de("1.01"));
    }

    @Test
    void valor_nulo_e_rejeitado() {
        assertThatThrownBy(() -> new Dinheiro(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void texto_invalido_e_rejeitado() {
        assertThatThrownBy(() -> Dinheiro.de("doze reais")).isInstanceOf(NumberFormatException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.5", "10.50", "10.500", " 10.50 ", "-3.25", "0"})
    void exato_aceita_ate_2_casas_significativas(String texto) {
        assertThat(Dinheiro.exato(texto)).isEqualTo(Dinheiro.de(texto.strip()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.505", "0.001", "abc", "", " ", "10,50", "1e"})
    void exato_recusa_em_vez_de_arredondar(String texto) {
        assertThatThrownBy(() -> Dinheiro.exato(texto)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exato_recusa_nulo() {
        assertThatThrownBy(() -> Dinheiro.exato(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void somar_e_subtrair_o_mesmo_valor_volta_ao_inicio(
            @ForAll @Scale(2) @BigRange(min = "-1000000000", max = "1000000000") BigDecimal a,
            @ForAll @Scale(2) @BigRange(min = "-1000000000", max = "1000000000") BigDecimal b) {
        var x = new Dinheiro(a);
        var y = new Dinheiro(b);
        assertThat(x.mais(y).menos(y)).isEqualTo(x);
    }
}
