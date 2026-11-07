package br.dev.azimute.plataforma.web;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Decisão T2: dinheiro e competência trafegam como string. */
class JacksonKernelConfigTest {

    private final JsonMapper json = JsonMapper.builder()
            .addModule(new JacksonKernelConfig().moduloDoKernel())
            .build();

    record Exemplo(Dinheiro valor, Competencia competencia) {}

    @Test
    void dinheiro_e_competencia_serializam_como_string() {
        var texto = json.writeValueAsString(new Exemplo(Dinheiro.de("17430"), Competencia.de("2026-09")));
        assertThat(texto).isEqualTo("{\"valor\":\"17430.00\",\"competencia\":\"2026-09\"}");
    }

    @Test
    void dinheiro_e_competencia_desserializam_de_string() {
        var exemplo = json.readValue("{\"valor\":\"860.69\",\"competencia\":\"2026-09\"}", Exemplo.class);
        assertThat(exemplo.valor()).isEqualTo(Dinheiro.de("860.69"));
        assertThat(exemplo.competencia()).isEqualTo(Competencia.de("2026-09"));
    }

    @Test
    void dinheiro_numerico_e_lido_sem_passar_por_double() {
        var exemplo = json.readValue("{\"valor\":0.1}", Exemplo.class);
        assertThat(exemplo.valor()).isEqualTo(Dinheiro.de("0.10"));
    }

    @Test
    void competencia_fora_do_formato_e_rejeitada() {
        assertThatThrownBy(() -> json.readValue("{\"competencia\":\"2026-09-01\"}", Exemplo.class))
                .hasMessageContaining("yyyy-MM");
    }

    @Test
    void dinheiro_invalido_e_rejeitado() {
        assertThatThrownBy(() -> json.readValue("{\"valor\":\"doze\"}", Exemplo.class))
                .isInstanceOf(Exception.class);
    }
}
