package br.dev.azimute.contas;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RF10–RF15, P1, T2, T3, T9: regras de lançamento, reenvio, exclusão lógica e restauração, sem banco. */
class MovimentacaoServiceTest {

    private static final LocalDate DATA = LocalDate.of(2026, 11, 10);

    private final MovimentacaoRepository movimentacoes = mock(MovimentacaoRepository.class);
    private final ContaRepository contas = mock(ContaRepository.class);
    private final ConciliacaoRepository conciliacoes = mock(ConciliacaoRepository.class);
    private final ContaService saldos = mock(ContaService.class);
    private final MovimentacaoService servico = new MovimentacaoService(movimentacoes, contas, conciliacoes, saldos);

    private final ContaId nubank = conta(true);
    private final ContaId cdb = conta(true);
    private final MovimentacaoId id = new MovimentacaoId(UUID.randomUUID());

    @Test
    void transferencia_nova_e_gravada() {
        when(movimentacoes.buscar(id)).thenReturn(Optional.empty());
        when(movimentacoes.inserirSeAusente(any())).thenReturn(true);

        var gravada = servico.registrar(id, transferencia("1500"));

        assertThat(gravada).isEqualTo(new Movimentacao.Transferencia(id, nubank, cdb, Dinheiro.de("1500"), DATA,
                "reserva"));
        verify(movimentacoes).inserirSeAusente(gravada);
    }

    @Test
    void reenvio_com_o_mesmo_corpo_devolve_a_existente_sem_inserir() {
        var existente = new Movimentacao.Transferencia(id, nubank, cdb, Dinheiro.de("1500"), DATA, "reserva");
        when(movimentacoes.buscar(id)).thenReturn(Optional.of(new MovimentacaoRepository.Registro(existente, false)));

        assertThat(servico.registrar(id, transferencia("1500"))).isEqualTo(existente);
        verify(movimentacoes, never()).inserirSeAusente(any());
    }

    @Test
    void reenvio_com_valor_diferente_e_divergente() {
        var existente = new Movimentacao.Transferencia(id, nubank, cdb, Dinheiro.de("1500"), DATA, "reserva");
        when(movimentacoes.buscar(id)).thenReturn(Optional.of(new MovimentacaoRepository.Registro(existente, false)));

        assertThatThrownBy(() -> servico.registrar(id, transferencia("15")))
                .isInstanceOfSatisfying(MovimentacaoDivergenteException.class,
                        e -> assertThat(e.codigo()).isEqualTo("MOVIMENTACAO_DIVERGENTE"));
    }

    @Test
    void reenvio_de_movimentacao_excluida_nao_a_ressuscita() {
        var existente = new Movimentacao.Transferencia(id, nubank, cdb, Dinheiro.de("1500"), DATA, "reserva");
        when(movimentacoes.buscar(id)).thenReturn(Optional.of(new MovimentacaoRepository.Registro(existente, true)));

        assertThatThrownBy(() -> servico.registrar(id, transferencia("1500")))
                .isInstanceOfSatisfying(MovimentacaoExcluidaException.class,
                        e -> assertThat(e.codigo()).isEqualTo("MOVIMENTACAO_EXCLUIDA"));
    }

    @Test
    void corrida_entre_dois_envios_iguais_devolve_o_que_ganhou() {
        var existente = new Movimentacao.Transferencia(id, nubank, cdb, Dinheiro.de("1500"), DATA, "reserva");
        when(movimentacoes.buscar(id)).thenReturn(Optional.empty(),
                Optional.of(new MovimentacaoRepository.Registro(existente, false)));
        when(movimentacoes.inserirSeAusente(any())).thenReturn(false);

        assertThat(servico.registrar(id, transferencia("1500"))).isEqualTo(existente);
    }

    @Test
    void so_transferencia_e_rendimento_entram_pela_api() {
        when(movimentacoes.buscar(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.registrar(id,
                new NovaMovimentacao("SAIDA", nubank, null, Dinheiro.de("10"), DATA, null)))
                .isInstanceOfSatisfying(TipoNaoPermitidoException.class,
                        e -> assertThat(e.codigo()).isEqualTo("TIPO_NAO_PERMITIDO"));
    }

    @Test
    void transferencia_sem_destino_e_rendimento_sem_destino_sao_dados_invalidos() {
        when(movimentacoes.buscar(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.registrar(id,
                new NovaMovimentacao("TRANSFERENCIA", nubank, null, Dinheiro.de("10"), DATA, null)))
                .isInstanceOf(DadosInvalidosException.class);
        assertThatThrownBy(() -> servico.registrar(id,
                new NovaMovimentacao("RENDIMENTO", null, null, Dinheiro.de("10"), DATA, null)))
                .isInstanceOf(DadosInvalidosException.class);
        assertThatThrownBy(() -> servico.registrar(id,
                new NovaMovimentacao("RENDIMENTO", nubank, cdb, Dinheiro.de("10"), DATA, null)))
                .isInstanceOf(DadosInvalidosException.class);
    }

    @Test
    void transferencia_para_a_mesma_conta_e_recusada() {
        when(movimentacoes.buscar(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.registrar(id,
                new NovaMovimentacao("TRANSFERENCIA", nubank, nubank, Dinheiro.de("10"), DATA, null)))
                .isInstanceOfSatisfying(TransferenciaParaAMesmaContaException.class,
                        e -> assertThat(e.codigo()).isEqualTo("TRANSFERENCIA_PARA_A_MESMA_CONTA"));
    }

    @Test
    void conta_inativa_e_conta_inexistente_sao_recusadas() {
        when(movimentacoes.buscar(id)).thenReturn(Optional.empty());
        var inativa = conta(false);
        var inexistente = new ContaId(UUID.randomUUID());
        when(contas.buscar(inexistente)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.registrar(id,
                new NovaMovimentacao("RENDIMENTO", null, inativa, Dinheiro.de("10"), DATA, null)))
                .isInstanceOfSatisfying(ContaInativaException.class,
                        e -> assertThat(e.codigo()).isEqualTo("CONTA_INATIVA"));
        assertThatThrownBy(() -> servico.registrar(id,
                new NovaMovimentacao("RENDIMENTO", null, inexistente, Dinheiro.de("10"), DATA, null)))
                .isInstanceOf(ContaNaoEncontradaException.class);
    }

    @Test
    void motivo_em_branco_vira_nulo() {
        when(movimentacoes.buscar(id)).thenReturn(Optional.empty());
        when(movimentacoes.inserirSeAusente(any())).thenReturn(true);

        var gravada = servico.registrar(id,
                new NovaMovimentacao("TRANSFERENCIA", nubank, cdb, Dinheiro.de("1"), DATA, "   "));

        assertThat(((Movimentacao.Transferencia) gravada).motivo()).isNull();
    }

    @Test
    void excluir_marca_como_excluida_e_e_idempotente() {
        var viva = new Movimentacao.Rendimento(id, cdb, Dinheiro.de("42"), DATA);
        when(movimentacoes.buscar(id)).thenReturn(Optional.of(new MovimentacaoRepository.Registro(viva, false)));
        servico.excluir(id);
        verify(movimentacoes).excluir(id);

        var outra = new MovimentacaoId(UUID.randomUUID());
        when(movimentacoes.buscar(outra)).thenReturn(Optional.of(new MovimentacaoRepository.Registro(
                new Movimentacao.Rendimento(outra, cdb, Dinheiro.de("42"), DATA), true)));
        servico.excluir(outra);
        verify(movimentacoes, never()).excluir(outra);

        var inexistente = new MovimentacaoId(UUID.randomUUID());
        when(movimentacoes.buscar(inexistente)).thenReturn(Optional.empty());
        servico.excluir(inexistente);
        verify(movimentacoes, never()).excluir(inexistente);
    }

    @Test
    void excluir_ou_restaurar_movimentacao_de_outro_modulo_e_recusado() {
        var saida = new Movimentacao.Saida(id, nubank, UUID.randomUUID(), Dinheiro.de("10"), DATA);
        when(movimentacoes.buscar(id)).thenReturn(Optional.of(new MovimentacaoRepository.Registro(saida, false)));
        var faturaId = new MovimentacaoId(UUID.randomUUID());
        var fatura = new Movimentacao.PagamentoFatura(faturaId, nubank, UUID.randomUUID(), Competencia.de("2026-11"),
                Dinheiro.de("10"), DATA);
        when(movimentacoes.buscar(faturaId)).thenReturn(Optional.of(new MovimentacaoRepository.Registro(fatura, true)));

        assertThatThrownBy(() -> servico.excluir(id))
                .isInstanceOfSatisfying(MovimentacaoDeOutroModuloException.class,
                        e -> assertThat(e.codigo()).isEqualTo("MOVIMENTACAO_DE_OUTRO_MODULO"));
        assertThatThrownBy(() -> servico.restaurar(faturaId)).isInstanceOf(MovimentacaoDeOutroModuloException.class);
    }

    @Test
    void restaurar_volta_a_movimentacao_excluida() {
        var excluida = new Movimentacao.Rendimento(id, cdb, Dinheiro.de("42"), DATA);
        when(movimentacoes.buscar(id)).thenReturn(Optional.of(new MovimentacaoRepository.Registro(excluida, true)));

        assertThat(servico.restaurar(id)).isEqualTo(excluida);
        verify(movimentacoes).restaurar(id);
    }

    @Test
    void restaurar_viva_nao_muda_nada_e_inexistente_e_404() {
        var viva = new Movimentacao.Rendimento(id, cdb, Dinheiro.de("42"), DATA);
        when(movimentacoes.buscar(id)).thenReturn(Optional.of(new MovimentacaoRepository.Registro(viva, false)));
        var inexistente = new MovimentacaoId(UUID.randomUUID());
        when(movimentacoes.buscar(inexistente)).thenReturn(Optional.empty());

        assertThat(servico.restaurar(id)).isEqualTo(viva);
        verify(movimentacoes, never()).restaurar(id);
        assertThatThrownBy(() -> servico.restaurar(inexistente))
                .isInstanceOfSatisfying(MovimentacaoNaoEncontradaException.class,
                        e -> assertThat(e.codigo()).isEqualTo("MOVIMENTACAO_NAO_ENCONTRADA"));
    }

    @Test
    void extrato_com_periodo_invertido_e_recusado() {
        assertThatThrownBy(() -> servico.extrato(nubank, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 30)))
                .isInstanceOfSatisfying(PeriodoInvalidoException.class,
                        e -> assertThat(e.codigo()).isEqualTo("PERIODO_INVALIDO"));
    }

    private NovaMovimentacao transferencia(String valor) {
        return new NovaMovimentacao("TRANSFERENCIA", nubank, cdb, Dinheiro.de(valor), DATA, "reserva");
    }

    private ContaId conta(boolean ativa) {
        var id = new ContaId(UUID.randomUUID());
        when(contas.buscar(id)).thenReturn(Optional.of(new Conta(id, "Conta " + id, "Banco", TipoConta.CORRENTE,
                Funcao.GIRO, Dinheiro.ZERO, ativa)));
        return id;
    }
}
