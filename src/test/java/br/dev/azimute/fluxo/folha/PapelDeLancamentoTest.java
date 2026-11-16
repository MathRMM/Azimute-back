package br.dev.azimute.fluxo.folha;

import br.dev.azimute.ChecksDoBanco;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** FGTS e as três entradas da previdência (RN19), com os nomes de {@code fluxo.lancamentos_folha.papel}. */
class PapelDeLancamentoTest {

    @Test
    void nomes_batem_com_o_check_do_banco() {
        assertThat(PapelDeLancamento.values()).extracting(Enum::name).containsExactlyElementsOf(
                ChecksDoBanco.valores("V007__fluxo.sql", "fluxo.lancamentos_folha", "papel"));
    }
}
