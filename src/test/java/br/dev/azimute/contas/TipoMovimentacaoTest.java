package br.dev.azimute.contas;

import br.dev.azimute.ChecksDoBanco;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** RF14: os cinco tipos, com os nomes que o banco aceita em {@code contas.movimentacoes.tipo}. */
class TipoMovimentacaoTest {

    @Test
    void nomes_batem_com_o_check_do_banco() {
        assertThat(TipoMovimentacao.values()).extracting(Enum::name).containsExactlyElementsOf(
                ChecksDoBanco.valores("V004__contas.sql", "contas.movimentacoes", "tipo"));
    }

    @Test
    void texto_da_api_vira_tipo_ou_vazio() {
        assertThat(TipoMovimentacao.doTexto("TRANSFERENCIA")).contains(TipoMovimentacao.TRANSFERENCIA);
        assertThat(TipoMovimentacao.doTexto("transferencia")).isEqualTo(Optional.empty());
        assertThat(TipoMovimentacao.doTexto("PIX")).isEmpty();
        assertThat(TipoMovimentacao.doTexto(null)).isEmpty();
    }
}
