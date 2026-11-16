package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RF06, RF32, RN01, RN11, RN12, RN13, P7: posição geral e saldo em conta, sem banco. */
class ConsolidadoServiceTest {

    private final ContaService contas = mock(ContaService.class);

    @Test
    void posicao_geral_soma_todas_as_contas_e_saldo_em_conta_so_as_correntes_positivas() {
        when(contas.listar(false)).thenReturn(List.of(
                item(TipoConta.CORRENTE, Funcao.GIRO, "2300", "0"),
                item(TipoConta.INVESTIMENTO, Funcao.RESERVA, "9000", "0"),
                item(TipoConta.CORRENTE, Funcao.GIRO, "-300", "1000")));

        var consolidado = new ConsolidadoService(contas, List.of()).consolidado();

        assertThat(consolidado.posicaoGeral()).isEqualTo(Dinheiro.de("11000"));
        assertThat(consolidado.dividas()).isEqualTo(Dinheiro.ZERO);
        assertThat(consolidado.saldoEmConta()).isEqualTo(Dinheiro.de("2300"));
        assertThat(consolidado.contas()).hasSize(3);
    }

    @Test
    void dividas_de_credito_entram_descontando_e_a_posicao_pode_ficar_negativa() {
        when(contas.listar(false)).thenReturn(List.of(
                item(TipoConta.CORRENTE, Funcao.GIRO, "2300", "0"),
                item(TipoConta.INVESTIMENTO, Funcao.RESERVA, "9000", "0"),
                item(TipoConta.CORRENTE, Funcao.GIRO, "-300", "1000")));
        FontesDeDivida cartaoA = () -> Dinheiro.de("7000");
        FontesDeDivida cartaoB = () -> Dinheiro.de("5000");

        var consolidado = new ConsolidadoService(contas, List.of(cartaoA, cartaoB)).consolidado();

        assertThat(consolidado.dividas()).isEqualTo(Dinheiro.de("12000"));
        assertThat(consolidado.posicaoGeral()).isEqualTo(Dinheiro.de("-1000"));
        assertThat(consolidado.saldoEmConta()).isEqualTo(Dinheiro.de("2300"));
    }

    @Test
    void cheque_especial_nao_e_descontado_duas_vezes() {
        when(contas.listar(false)).thenReturn(List.of(item(TipoConta.CORRENTE, Funcao.GIRO, "-300", "1000")));

        var consolidado = new ConsolidadoService(contas, List.of()).consolidado();

        assertThat(consolidado.posicaoGeral()).isEqualTo(Dinheiro.de("-300"));
        assertThat(consolidado.saldoEmConta()).isEqualTo(Dinheiro.ZERO);
    }

    @Test
    void sem_contas_tudo_e_zero() {
        when(contas.listar(false)).thenReturn(List.of());

        var consolidado = new ConsolidadoService(contas, List.of()).consolidado();

        assertThat(consolidado.posicaoGeral()).isEqualTo(Dinheiro.ZERO);
        assertThat(consolidado.saldoEmConta()).isEqualTo(Dinheiro.ZERO);
        assertThat(consolidado.contas()).isEmpty();
    }

    @Test
    void carteira_e_beneficio_entram_na_posicao_geral_mas_nao_no_saldo_em_conta() {
        when(contas.listar(false)).thenReturn(List.of(
                item(TipoConta.CARTEIRA, Funcao.GIRO, "80", "0"),
                item(TipoConta.BENEFICIO, Funcao.GIRO, "600", "0"),
                item(TipoConta.POUPANCA, Funcao.RESERVA, "-10", "0")));

        var consolidado = new ConsolidadoService(contas, List.of()).consolidado();

        assertThat(consolidado.posicaoGeral()).isEqualTo(Dinheiro.de("670"));
        assertThat(consolidado.saldoEmConta()).isEqualTo(Dinheiro.ZERO);
    }

    @Test
    void fgts_e_previdencia_ficam_so_no_bloqueado() {
        when(contas.listar(false)).thenReturn(List.of(
                item(TipoConta.FGTS, Funcao.RESERVA, "1000", "0"),
                item(TipoConta.CORRENTE, Funcao.GIRO, "500", "0")));

        var consolidado = new ConsolidadoService(contas, List.of()).consolidado();

        assertThat(consolidado.posicaoGeral()).isEqualTo(Dinheiro.de("500"));
        assertThat(consolidado.saldoEmConta()).isEqualTo(Dinheiro.de("500"));
        assertThat(consolidado.bloqueado()).isEqualTo(Dinheiro.de("1000"));
        assertThat(consolidado.contas()).hasSize(2);
    }

    @Test
    void bloqueado_soma_fgts_e_previdencia_e_as_dividas_nao_o_tocam() {
        when(contas.listar(false)).thenReturn(List.of(
                item(TipoConta.FGTS, Funcao.RESERVA, "400", "0"),
                item(TipoConta.PREVIDENCIA, Funcao.RESERVA, "350", "0"),
                item(TipoConta.CORRENTE, Funcao.GIRO, "100", "0")));
        FontesDeDivida cartao = () -> Dinheiro.de("300");

        var consolidado = new ConsolidadoService(contas, List.of(cartao)).consolidado();

        assertThat(consolidado.bloqueado()).isEqualTo(Dinheiro.de("750"));
        assertThat(consolidado.posicaoGeral()).isEqualTo(Dinheiro.de("-200"));
    }

    @Test
    void sem_fgts_nem_previdencia_o_bloqueado_e_zero() {
        when(contas.listar(false)).thenReturn(List.of(item(TipoConta.CORRENTE, Funcao.GIRO, "100", "0")));

        assertThat(new ConsolidadoService(contas, List.of()).consolidado().bloqueado()).isEqualTo(Dinheiro.ZERO);
    }

    private static ContaComSaldo item(TipoConta tipo, Funcao funcao, String saldo, String limite) {
        var conta = new Conta(new ContaId(UUID.randomUUID()), "Conta " + UUID.randomUUID(), "Banco", tipo, funcao,
                Dinheiro.de(limite), true);
        var base = new Conciliacao(new ConciliacaoId(UUID.randomUUID()), conta.id(), LocalDate.of(2026, 11, 1),
                Dinheiro.de(saldo), Dinheiro.de(saldo), Dinheiro.ZERO, null);
        return new ContaComSaldo(conta, CalculoDeSaldo.calcular(conta, base, Dinheiro.ZERO, 0));
    }
}
