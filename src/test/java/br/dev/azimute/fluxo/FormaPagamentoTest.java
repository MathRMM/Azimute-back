package br.dev.azimute.fluxo;

import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.folha.HoleriteId;
import br.dev.azimute.fluxo.tabelas.FormaDeGasto;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF14, RNF02: a forma de pagamento é um tipo fechado; o {@code switch} cobre as duas formas sem {@code default}. */
class FormaPagamentoTest {

    private static final ContaId CONTA = new ContaId(UUID.randomUUID());
    private static final HoleriteId HOLERITE = new HoleriteId(UUID.randomUUID());

    /** Sem {@code default}: uma forma nova (o crédito da fase 5) deixa este teste sem compilar. */
    private static String descrever(FormaPagamento forma) {
        return switch (forma) {
            case FormaPagamento.AVista a -> "conta " + a.conta();
            case FormaPagamento.DescontoEmFolha d -> "holerite " + d.holerite();
        };
    }

    @Test
    void switch_cobre_as_duas_formas() {
        assertThat(descrever(new FormaPagamento.AVista(CONTA))).isEqualTo("conta " + CONTA);
        assertThat(descrever(new FormaPagamento.DescontoEmFolha(HOLERITE))).isEqualTo("holerite " + HOLERITE);
    }

    @Test
    void codigo_e_o_gravado_no_banco() {
        assertThat(new FormaPagamento.AVista(CONTA).codigo()).isEqualTo(FormaDeGasto.A_VISTA);
        assertThat(new FormaPagamento.DescontoEmFolha(HOLERITE).codigo()).isEqualTo(FormaDeGasto.DESCONTO_EM_FOLHA);
    }

    @Test
    void cada_forma_exige_a_sua_referencia() {
        assertThatThrownBy(() -> new FormaPagamento.AVista(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FormaPagamento.DescontoEmFolha(null)).isInstanceOf(NullPointerException.class);
    }
}
