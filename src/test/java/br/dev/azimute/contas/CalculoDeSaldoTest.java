package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** RN06, RN11, P8: saldo a partir da base e cheque especial só em conta corrente, sem banco. */
class CalculoDeSaldoTest {

    private static final LocalDate BASE_EM = LocalDate.of(2026, 11, 1);

    @Test
    void saldo_e_a_base_mais_o_efeito_das_movimentacoes_posteriores() {
        var saldo = CalculoDeSaldo.calcular(poupanca(), base("1000"), Dinheiro.de("150"), 2);

        assertThat(saldo.atual()).isEqualTo(Dinheiro.de("1150"));
        assertThat(saldo.baseEm()).isEqualTo(BASE_EM);
        assertThat(saldo.baseValor()).isEqualTo(Dinheiro.de("1000"));
        assertThat(saldo.movimentacoesDesdeABase()).isEqualTo(2);
    }

    @Test
    void corrente_negativa_dentro_do_limite_usa_o_cheque_especial() {
        var saldo = CalculoDeSaldo.calcular(corrente("1000"), base("200"), Dinheiro.de("-500"), 1);

        assertThat(saldo.atual()).isEqualTo(Dinheiro.de("-300"));
        assertThat(saldo.chequeEspecial().emUso()).isEqualTo(Dinheiro.de("300"));
        assertThat(saldo.chequeEspecial().disponivel()).isEqualTo(Dinheiro.de("700"));
        assertThat(saldo.chequeEspecial().acimaDoLimite()).isFalse();
    }

    @Test
    void corrente_negativa_alem_do_limite_e_marcada_sem_bloquear() {
        var saldo = CalculoDeSaldo.calcular(corrente("1000"), base("0"), Dinheiro.de("-1200"), 1);

        assertThat(saldo.atual()).isEqualTo(Dinheiro.de("-1200"));
        assertThat(saldo.chequeEspecial().emUso()).isEqualTo(Dinheiro.de("1000"));
        assertThat(saldo.chequeEspecial().disponivel()).isEqualTo(Dinheiro.ZERO);
        assertThat(saldo.chequeEspecial().acimaDoLimite()).isTrue();
    }

    @Test
    void corrente_negativa_exatamente_no_limite_nao_esta_acima() {
        var saldo = CalculoDeSaldo.calcular(corrente("1000"), base("0"), Dinheiro.de("-1000"), 1);

        assertThat(saldo.chequeEspecial().disponivel()).isEqualTo(Dinheiro.ZERO);
        assertThat(saldo.chequeEspecial().acimaDoLimite()).isFalse();
    }

    @Test
    void corrente_sem_limite_negativa_ja_esta_acima_do_limite() {
        var saldo = CalculoDeSaldo.calcular(corrente("0"), base("0"), Dinheiro.de("-1"), 1);

        assertThat(saldo.chequeEspecial().emUso()).isEqualTo(Dinheiro.ZERO);
        assertThat(saldo.chequeEspecial().acimaDoLimite()).isTrue();
    }

    @Test
    void corrente_positiva_tem_o_limite_inteiro_disponivel() {
        var saldo = CalculoDeSaldo.calcular(corrente("1000"), base("2300"), Dinheiro.ZERO, 0);

        assertThat(saldo.chequeEspecial().limite()).isEqualTo(Dinheiro.de("1000"));
        assertThat(saldo.chequeEspecial().emUso()).isEqualTo(Dinheiro.ZERO);
        assertThat(saldo.chequeEspecial().disponivel()).isEqualTo(Dinheiro.de("1000"));
        assertThat(saldo.chequeEspecial().acimaDoLimite()).isFalse();
    }

    @Test
    void conta_que_nao_e_corrente_fica_negativa_sem_cheque_especial() {
        var saldo = CalculoDeSaldo.calcular(poupanca(), base("100"), Dinheiro.de("-250"), 1);

        assertThat(saldo.atual()).isEqualTo(Dinheiro.de("-150"));
        assertThat(saldo.chequeEspecial()).isNull();
    }

    private static Conta corrente(String limite) {
        return new Conta(new ContaId(UUID.randomUUID()), "Nubank", "Nu", TipoConta.CORRENTE, Funcao.GIRO,
                Dinheiro.de(limite), true);
    }

    private static Conta poupanca() {
        return new Conta(new ContaId(UUID.randomUUID()), "Poupança", "Caixa", TipoConta.POUPANCA, Funcao.RESERVA,
                Dinheiro.ZERO, true);
    }

    private static Conciliacao base(String saldoReal) {
        return new Conciliacao(new ConciliacaoId(UUID.randomUUID()), new ContaId(UUID.randomUUID()), BASE_EM,
                Dinheiro.de(saldoReal), Dinheiro.de(saldoReal), Dinheiro.ZERO, null);
    }
}
