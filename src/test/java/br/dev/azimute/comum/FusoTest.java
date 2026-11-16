package br.dev.azimute.comum;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** O instante do domínio sai na API no fuso de São Paulo, e o "hoje" não vira o dia seguinte às 22h. */
class FusoTest {

    @Test
    void instante_sai_na_api_com_o_deslocamento_de_sao_paulo() {
        var instante = Instant.parse("2026-11-16T01:30:00Z");

        assertThat(Fuso.naApi(instante))
                .isEqualTo(OffsetDateTime.of(2026, 11, 15, 22, 30, 0, 0, ZoneOffset.ofHours(-3)));
        assertThat(Fuso.naApi(instante).toInstant()).isEqualTo(instante);
    }

    @Test
    void nulo_continua_nulo() {
        assertThat(Fuso.naApi(null)).isNull();
    }

    @Test
    void hoje_usa_o_fuso_do_sistema_e_nao_o_do_relogio() {
        var relogioEmUtc = Clock.fixed(Instant.parse("2026-11-16T01:30:00Z"), ZoneOffset.UTC);

        assertThat(Fuso.hoje(relogioEmUtc)).isEqualTo(LocalDate.of(2026, 11, 15));
    }
}
