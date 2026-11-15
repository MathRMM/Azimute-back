package br.dev.azimute.comum;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF01–RF03, RF06: bordas dos tipos do kernel que os testes de formato não cobrem. */
class BordasDoKernelTest {

    @Test
    void competencia_nula_e_recusada_com_mensagem_de_formato() {
        assertThatThrownBy(() -> Competencia.de(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM");
    }

    @Test
    void percentual_imprime_com_o_simbolo() {
        assertThat(new Percentual(45).toString()).isEqualTo("45%");
    }

    @Test
    void erro_de_negocio_sem_detalhes_tem_mapa_vazio() {
        var erro = new ErroDeNegocio("CODIGO", "mensagem", ErroDeNegocio.Tipo.CONFLITO, null) {};

        assertThat(erro.detalhes()).isEmpty();
    }
}
