package br.dev.azimute.fluxo.tabelas;

import br.dev.azimute.ChecksDoBanco;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Os enums gravados como texto em {@code fluxo} têm exatamente os nomes que o banco aceita. */
class EnumsDasTabelasTest {

    private static final String MIGRATION = "V007__fluxo.sql";

    @Test
    void papel_de_recebimento_bate_com_o_check_do_banco() {
        assertThat(PapelDeRecebimento.values()).extracting(Enum::name).containsExactlyElementsOf(
                ChecksDoBanco.valores(MIGRATION, "fluxo.recebimentos", "papel"));
    }

    @Test
    void forma_de_gasto_bate_com_o_check_do_banco() {
        assertThat(FormaDeGasto.values()).extracting(Enum::name).containsExactlyElementsOf(
                ChecksDoBanco.valores(MIGRATION, "fluxo.gastos", "forma"));
    }
}
