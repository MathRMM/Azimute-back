package br.dev.azimute.fluxo.tabelas;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.CategoriaId;
import br.dev.azimute.fluxo.folha.HoleriteId;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** As linhas que as tabelas do fluxo gravam: a forma do gasto sai da conta ou do holerite. */
class LinhasDasTabelasTest {

    private static final LocalDate DIA = LocalDate.of(2026, 11, 16);
    private static final CategoriaId CATEGORIA = new CategoriaId(UUID.randomUUID());
    private static final ContaId CONTA = new ContaId(UUID.randomUUID());
    private static final HoleriteId HOLERITE = new HoleriteId(UUID.randomUUID());

    @Test
    void gasto_com_conta_e_a_vista_e_com_holerite_e_desconto_em_folha() {
        assertThat(gasto(CONTA, null).forma()).isEqualTo("A_VISTA");
        assertThat(gasto(null, HOLERITE).forma()).isEqualTo("DESCONTO_EM_FOLHA");
    }

    @Test
    void gasto_sem_conta_e_sem_holerite_ou_com_os_dois_e_recusado() {
        assertThatThrownBy(() -> gasto(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gasto(CONTA, HOLERITE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toda_linha_exige_id() {
        assertThatThrownBy(() -> new TabelaDeGastos.Linha(null, DIA, "x", Dinheiro.de("1"), CATEGORIA, CONTA, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TabelaDeGanhos.Linha(null, Competencia.de("2026-11"), "x", "OUTRO",
                Dinheiro.de("1"), Dinheiro.de("1"), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TabelaDeRecebimentos.Linha(null, null, Competencia.de("2026-11"), "UNICO",
                Dinheiro.de("1"), DIA, CONTA, false)).isInstanceOf(NullPointerException.class);
    }

    private static TabelaDeGastos.Linha gasto(ContaId conta, HoleriteId holerite) {
        return new TabelaDeGastos.Linha(UUID.randomUUID(), DIA, "Mercado", Dinheiro.de("10"), CATEGORIA, conta,
                holerite);
    }
}
