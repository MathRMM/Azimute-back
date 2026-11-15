package br.dev.azimute.comum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF02, RN03. */
class CompetenciaTest {

    @Test
    void proxima_cruza_o_ano() {
        assertThat(Competencia.de("2026-12").proxima()).isEqualTo(Competencia.de("2027-01"));
    }

    @Test
    void mais_meses_cruza_o_ano() {
        assertThat(Competencia.de("2026-09").mais(14)).isEqualTo(Competencia.de("2027-11"));
        assertThat(Competencia.de("2026-09").mais(-9)).isEqualTo(Competencia.de("2025-12"));
    }

    @Test
    void intervalo_inclui_as_duas_pontas() {
        assertThat(Competencia.de("2026-11").ate(Competencia.de("2027-02")))
                .extracting(Competencia::toString)
                .containsExactly("2026-11", "2026-12", "2027-01", "2027-02");
    }

    @Test
    void intervalo_de_um_mes_so() {
        assertThat(Competencia.de("2026-09").ate(Competencia.de("2026-09"))).hasSize(1);
    }

    @Test
    void intervalo_invertido_e_vazio() {
        assertThat(Competencia.de("2026-09").ate(Competencia.de("2026-08"))).isEmpty();
    }

    @Test
    void ida_e_volta_no_formato_da_api() {
        assertThat(Competencia.de("2026-09").toString()).isEqualTo("2026-09");
        assertThat(Competencia.de("2026-09").mes()).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test
    void ordena_cronologicamente() {
        assertThat(Competencia.de("2026-09")).isLessThan(Competencia.de("2026-10"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-9", "09/2026", "2026-09-01", "set/2026", "2026-13", "", " 2026-09"})
    void formatos_fora_do_padrao_sao_rejeitados(String texto) {
        assertThatThrownBy(() -> Competencia.de(texto)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nulo_e_rejeitado() {
        assertThatThrownBy(() -> new Competencia(null)).isInstanceOf(NullPointerException.class);
    }
}
