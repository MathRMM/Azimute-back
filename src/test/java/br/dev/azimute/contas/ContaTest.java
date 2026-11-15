package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF02: a conta nasce com os campos obrigatórios preenchidos. */
class ContaTest {

    private static final ContaId ID = new ContaId(UUID.randomUUID());

    @Test
    void conta_completa_guarda_os_valores() {
        var conta = new Conta(ID, "Nubank", "Nu Pagamentos", TipoConta.CORRENTE, Funcao.GIRO,
                Dinheiro.de("1000"), true);

        assertThat(conta.tipo()).isEqualTo(TipoConta.CORRENTE);
        assertThat(conta.funcao()).isEqualTo(Funcao.GIRO);
        assertThat(conta.limiteChequeEspecial()).isEqualTo(Dinheiro.de("1000"));
        assertThat(conta.ativa()).isTrue();
    }

    @Test
    void campos_obrigatorios_recusam_nulo() {
        assertThatThrownBy(() -> new Conta(null, "a", "b", TipoConta.CORRENTE, Funcao.GIRO, Dinheiro.ZERO, true))
                .hasMessage("id");
        assertThatThrownBy(() -> new Conta(ID, null, "b", TipoConta.CORRENTE, Funcao.GIRO, Dinheiro.ZERO, true))
                .hasMessage("apelido");
        assertThatThrownBy(() -> new Conta(ID, "a", "b", null, Funcao.GIRO, Dinheiro.ZERO, true))
                .hasMessage("tipo");
        assertThatThrownBy(() -> new Conta(ID, "a", "b", TipoConta.CORRENTE, null, Dinheiro.ZERO, true))
                .hasMessage("funcao");
        assertThatThrownBy(() -> new Conta(ID, "a", "b", TipoConta.CORRENTE, Funcao.GIRO, null, true))
                .hasMessage("limiteChequeEspecial");
    }
}
