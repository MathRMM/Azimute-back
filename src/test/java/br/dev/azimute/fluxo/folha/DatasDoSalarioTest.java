package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** RN18: adiantamento no dia 15 e líquido no último dia do mês, antecipando para o dia útil anterior. */
class DatasDoSalarioTest {

    @Test
    void outubro_de_2026_adiantamento_na_quinta_15_e_liquido_na_sexta_30() {
        var outubro = Competencia.de("2026-10");

        assertThat(DatasDoSalario.adiantamento(outubro)).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(DatasDoSalario.liquido(outubro)).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    @Test
    void dia_15_num_domingo_antecipa_para_a_sexta_13() {
        assertThat(DatasDoSalario.adiantamento(Competencia.de("2026-11"))).isEqualTo(LocalDate.of(2026, 11, 13));
    }

    @Test
    void ultimo_dia_util_e_o_proprio_quando_ja_e_util() {
        assertThat(DatasDoSalario.liquido(Competencia.de("2026-09"))).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(DatasDoSalario.liquido(Competencia.de("2027-02"))).isEqualTo(LocalDate.of(2027, 2, 26));
    }

    @Test
    void data_informada_prevalece_sobre_a_padrao() {
        var informada = LocalDate.of(2026, 10, 20);

        assertThat(DatasDoSalario.adiantamentoOu(informada, Competencia.de("2026-10"))).isEqualTo(informada);
        assertThat(DatasDoSalario.liquidoOu(informada, Competencia.de("2026-10"))).isEqualTo(informada);
        assertThat(DatasDoSalario.adiantamentoOu(null, Competencia.de("2026-10")))
                .isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(DatasDoSalario.liquidoOu(null, Competencia.de("2026-10"))).isEqualTo(LocalDate.of(2026, 10, 30));
    }
}
