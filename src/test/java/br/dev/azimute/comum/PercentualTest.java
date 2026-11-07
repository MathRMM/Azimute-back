package br.dev.azimute.comum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF03, RN02. */
class PercentualTest {

    @ParameterizedTest
    @ValueSource(ints = {-1, 101, Integer.MIN_VALUE})
    void fora_de_0_a_100_e_rejeitado(int pontos) {
        assertThatThrownBy(() -> new Percentual(pontos))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(pontos));
    }

    @Test
    void extremos_sao_aceitos() {
        assertThat(new Percentual(0).fracao()).isEqualByComparingTo("0");
        assertThat(new Percentual(100).fracao()).isEqualByComparingTo("1");
    }

    @Test
    void fracao_e_exata() {
        assertThat(new Percentual(45).fracao()).isEqualByComparingTo("0.45");
        assertThat(new Percentual(33).fracao()).isEqualByComparingTo("0.33");
    }
}
