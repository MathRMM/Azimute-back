package br.dev.azimute.comum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P5, T4 do SPEC do fluxo: fim de semana e feriados bancários nacionais, com os móveis a partir da Páscoa. */
class DiasUteisTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-11-14", // sábado
            "2026-11-15", // domingo
            "2026-01-01", "2026-04-21", "2026-05-01", "2026-09-07", "2026-10-12",
            "2026-11-02", "2026-11-20", "2026-12-25"})
    void fim_de_semana_e_feriado_fixo_nao_sao_uteis(String data) {
        assertThat(DiasUteis.ehDiaUtil(LocalDate.parse(data))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 2026: Páscoa em 5/4
            "2026-02-16", "2026-02-17", "2026-04-03", "2026-06-04",
            // 2027: Páscoa em 28/3
            "2027-02-08", "2027-02-09", "2027-03-26", "2027-05-27"})
    void carnaval_sexta_feira_santa_e_corpus_christi_nao_sao_uteis(String data) {
        assertThat(DiasUteis.ehDiaUtil(LocalDate.parse(data))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-11-16", // segunda comum
            "2026-02-18", // quarta-feira de cinzas
            "2026-04-06", // segunda depois da Páscoa
            "2026-12-31", // véspera de ano-novo não é feriado nacional
            "2026-06-05"})
    void dia_comum_de_semana_e_util(String data) {
        assertThat(DiasUteis.ehDiaUtil(LocalDate.parse(data))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-31, 2026-10-30", // sábado volta para sexta
            "2026-11-16, 2026-11-16", // dia útil fica
            "2026-11-15, 2026-11-13", // domingo volta para sexta
            "2026-11-22, 2026-11-19", // domingo, sábado, feriado de 20/11 na sexta → quinta
            "2026-04-05, 2026-04-02", // Páscoa, sábado e Sexta-feira Santa → quinta
            "2026-02-17, 2026-02-13", // terça e segunda de Carnaval, fim de semana → sexta
            "2027-01-03, 2026-12-31"}) // atravessa o ano
    void dia_util_anterior_ou_o_mesmo(String data, String esperado) {
        assertThat(DiasUteis.diaUtilAnteriorOuMesmo(LocalDate.parse(data))).isEqualTo(LocalDate.parse(esperado));
    }

    @Test
    void pascoa_de_varios_anos_pelo_calculo() {
        assertThat(DiasUteis.pascoa(2024)).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(DiasUteis.pascoa(2025)).isEqualTo(LocalDate.of(2025, 4, 20));
        assertThat(DiasUteis.pascoa(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(DiasUteis.pascoa(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
        assertThat(DiasUteis.pascoa(2038)).isEqualTo(LocalDate.of(2038, 4, 25));
    }

    @Test
    void data_nula_e_recusada() {
        assertThatThrownBy(() -> DiasUteis.ehDiaUtil(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DiasUteis.diaUtilAnteriorOuMesmo(null)).isInstanceOf(NullPointerException.class);
    }
}
