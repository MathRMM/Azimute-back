package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** RF01, RF04, RF05, RN11, P5: regras de criação e alteração de conta, com repositórios falsos. */
class ContaServiceTest {

    /** 02:30 em UTC já é dia 14, mas em São Paulo ainda é dia 13. */
    private static final Instant AGORA = Instant.parse("2026-11-14T02:30:00Z");
    private static final LocalDate HOJE_EM_SAO_PAULO = LocalDate.of(2026, 11, 13);

    private final ContaRepository contas = mock(ContaRepository.class);
    private final ConciliacaoRepository conciliacoes = mock(ConciliacaoRepository.class);
    private final MovimentacaoRepository movimentacoes = mock(MovimentacaoRepository.class);
    private final ContaService servico = new ContaService(contas, conciliacoes, movimentacoes,
            Clock.fixed(AGORA, ZoneId.of("UTC")));

    @Test
    void criar_grava_a_conta_e_a_conciliacao_inicial_na_data_de_hoje_em_sao_paulo() {
        var id = new ContaId(UUID.randomUUID());
        when(contas.inserir(any(), any(), any(), any(), any())).thenReturn(id);
        prepararLeitura(conta(id, TipoConta.CORRENTE, "0", true), "2300");

        var criada = servico.criar(new NovaConta("  Nubank ", "Nu", TipoConta.CORRENTE, Funcao.GIRO,
                Dinheiro.de("2300"), null, null));

        verify(contas).inserir("Nubank", "Nu", TipoConta.CORRENTE, Funcao.GIRO, Dinheiro.ZERO);
        verify(conciliacoes).inserir(id, HOJE_EM_SAO_PAULO, Dinheiro.de("2300"), Dinheiro.de("2300"),
                Dinheiro.ZERO, null);
        assertThat(criada.saldo().atual()).isEqualTo(Dinheiro.de("2300"));
    }

    @Test
    void criar_usa_a_data_do_saldo_inicial_quando_informada() {
        var id = new ContaId(UUID.randomUUID());
        var data = LocalDate.of(2026, 10, 31);
        when(contas.inserir(any(), any(), any(), any(), any())).thenReturn(id);
        prepararLeitura(conta(id, TipoConta.POUPANCA, "0", true), "-50");

        servico.criar(new NovaConta("Poupança", "Caixa", TipoConta.POUPANCA, Funcao.RESERVA,
                Dinheiro.de("-50"), data, null));

        verify(conciliacoes).inserir(id, data, Dinheiro.de("-50"), Dinheiro.de("-50"), Dinheiro.ZERO, null);
    }

    @Test
    void criar_com_limite_em_conta_que_nao_e_corrente_e_recusado_sem_gravar() {
        assertThatThrownBy(() -> servico.criar(new NovaConta("CDB", "Banco", TipoConta.INVESTIMENTO,
                Funcao.RESERVA, Dinheiro.ZERO, null, Dinheiro.de("500"))))
                .isInstanceOfSatisfying(LimiteSoEmContaCorrenteException.class,
                        e -> assertThat(e.codigo()).isEqualTo("LIMITE_SO_EM_CONTA_CORRENTE"));

        verifyNoInteractions(contas, conciliacoes);
    }

    @Test
    void criar_com_limite_zero_em_conta_que_nao_e_corrente_e_aceito() {
        var id = new ContaId(UUID.randomUUID());
        when(contas.inserir(any(), any(), any(), any(), any())).thenReturn(id);
        prepararLeitura(conta(id, TipoConta.CARTEIRA, "0", true), "40");

        servico.criar(new NovaConta("Carteira", "-", TipoConta.CARTEIRA, Funcao.GIRO, Dinheiro.de("40"), null,
                Dinheiro.ZERO));

        verify(contas).inserir("Carteira", "-", TipoConta.CARTEIRA, Funcao.GIRO, Dinheiro.ZERO);
    }

    @Test
    void alterar_muda_so_os_campos_informados() {
        var id = new ContaId(UUID.randomUUID());
        prepararLeitura(conta(id, TipoConta.CORRENTE, "0", true), "800");

        servico.alterar(id, new AlteracaoConta(null, null, Funcao.RESERVA, Dinheiro.de("1500"), null));

        var gravada = ArgumentCaptor.forClass(Conta.class);
        verify(contas).atualizar(gravada.capture());
        assertThat(gravada.getValue().apelido()).isEqualTo("Conta");
        assertThat(gravada.getValue().funcao()).isEqualTo(Funcao.RESERVA);
        assertThat(gravada.getValue().limiteChequeEspecial()).isEqualTo(Dinheiro.de("1500"));
        assertThat(gravada.getValue().ativa()).isTrue();
    }

    @Test
    void alterar_apelido_e_instituicao_tira_os_espacos_das_pontas() {
        var id = new ContaId(UUID.randomUUID());
        prepararLeitura(conta(id, TipoConta.CORRENTE, "0", true), "0");

        servico.alterar(id, new AlteracaoConta(" Itaú ", " Itaú Unibanco ", null, null, null));

        var gravada = ArgumentCaptor.forClass(Conta.class);
        verify(contas).atualizar(gravada.capture());
        assertThat(gravada.getValue().apelido()).isEqualTo("Itaú");
        assertThat(gravada.getValue().instituicao()).isEqualTo("Itaú Unibanco");
    }

    @Test
    void desativar_conta_com_saldo_e_permitido() {
        var id = new ContaId(UUID.randomUUID());
        prepararLeitura(conta(id, TipoConta.POUPANCA, "0", true), "12.34");

        servico.alterar(id, new AlteracaoConta(null, null, null, null, false));

        var gravada = ArgumentCaptor.forClass(Conta.class);
        verify(contas).atualizar(gravada.capture());
        assertThat(gravada.getValue().ativa()).isFalse();
    }

    @Test
    void alterar_limite_de_conta_que_nao_e_corrente_e_recusado() {
        var id = new ContaId(UUID.randomUUID());
        prepararLeitura(conta(id, TipoConta.POUPANCA, "0", true), "0");

        assertThatThrownBy(() -> servico.alterar(id, new AlteracaoConta(null, null, null, Dinheiro.de("1"), null)))
                .isInstanceOf(LimiteSoEmContaCorrenteException.class);
        verify(contas, never()).atualizar(any());
    }

    @Test
    void alterar_apelido_para_branco_e_recusado() {
        var id = new ContaId(UUID.randomUUID());
        prepararLeitura(conta(id, TipoConta.CORRENTE, "0", true), "0");

        assertThatThrownBy(() -> servico.alterar(id, new AlteracaoConta("   ", null, null, null, null)))
                .isInstanceOfSatisfying(DadosInvalidosException.class,
                        e -> assertThat(e.detalhes()).containsKey("campos"));
        verify(contas, never()).atualizar(any());
    }

    @Test
    void conta_inexistente_e_404_em_alterar_e_em_saldo() {
        var id = new ContaId(UUID.randomUUID());
        when(contas.buscar(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.alterar(id, new AlteracaoConta(null, null, null, null, false)))
                .isInstanceOfSatisfying(ContaNaoEncontradaException.class,
                        e -> assertThat(e.codigo()).isEqualTo("CONTA_NAO_ENCONTRADA"));
        assertThatThrownBy(() -> servico.saldo(id)).isInstanceOf(ContaNaoEncontradaException.class);
    }

    @Test
    void saldo_soma_as_movimentacoes_depois_da_ultima_conciliacao() {
        var id = new ContaId(UUID.randomUUID());
        when(contas.buscar(id)).thenReturn(Optional.of(conta(id, TipoConta.CORRENTE, "500", true)));
        var base = new Conciliacao(new ConciliacaoId(UUID.randomUUID()), id, LocalDate.of(2026, 11, 1),
                Dinheiro.de("100"), Dinheiro.de("100"), Dinheiro.ZERO, null);
        when(conciliacoes.ultima(id)).thenReturn(Optional.of(base));
        when(movimentacoes.resumoDesde(id, base.data()))
                .thenReturn(new MovimentacaoRepository.Resumo(Dinheiro.de("-300"), 3));

        var saldo = servico.saldo(id);

        assertThat(saldo.atual()).isEqualTo(Dinheiro.de("-200"));
        assertThat(saldo.movimentacoesDesdeABase()).isEqualTo(3);
        assertThat(saldo.chequeEspecial().emUso()).isEqualTo(Dinheiro.de("200"));
    }

    @Test
    void conta_sem_conciliacao_e_estado_impossivel() {
        var id = new ContaId(UUID.randomUUID());
        when(contas.buscar(id)).thenReturn(Optional.of(conta(id, TipoConta.CORRENTE, "0", true)));
        when(conciliacoes.ultima(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.saldo(id)).isInstanceOf(IllegalStateException.class);
    }

    private void prepararLeitura(Conta conta, String saldo) {
        when(contas.buscar(conta.id())).thenReturn(Optional.of(conta));
        var base = new Conciliacao(new ConciliacaoId(UUID.randomUUID()), conta.id(), HOJE_EM_SAO_PAULO,
                Dinheiro.de(saldo), Dinheiro.de(saldo), Dinheiro.ZERO, null);
        when(conciliacoes.ultima(conta.id())).thenReturn(Optional.of(base));
        when(movimentacoes.resumoDesde(conta.id(), base.data()))
                .thenReturn(new MovimentacaoRepository.Resumo(Dinheiro.ZERO, 0));
    }

    private static Conta conta(ContaId id, TipoConta tipo, String limite, boolean ativa) {
        return new Conta(id, "Conta", "Banco", tipo, tipo == TipoConta.CORRENTE ? Funcao.GIRO : Funcao.RESERVA,
                Dinheiro.de(limite), ativa);
    }
}
