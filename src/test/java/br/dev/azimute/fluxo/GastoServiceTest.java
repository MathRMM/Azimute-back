package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.contas.ContasFacade;
import br.dev.azimute.fluxo.folha.HoleriteId;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** RF10–RF13, RF15, RN04, RN05, RF52: o serviço de gastos sem banco, com a fachada de contas falsa. */
class GastoServiceTest {

    private static final LocalDate DIA = LocalDate.of(2026, 11, 14);
    private static final ContaId NUBANK = new ContaId(UUID.randomUUID());
    private static final ContaId INTER = new ContaId(UUID.randomUUID());
    private static final Categoria MERCADO = new Categoria(new CategoriaId(UUID.randomUUID()), "Mercado", false,
            true);
    private static final Categoria LAZER = new Categoria(new CategoriaId(UUID.randomUUID()), "Lazer", true, true);

    private final GastoRepository gastos = mock(GastoRepository.class);
    private final CategoriaService categorias = mock(CategoriaService.class);
    private final ContasFacade contas = mock(ContasFacade.class);
    private final ApplicationEventPublisher eventos = mock(ApplicationEventPublisher.class);
    private final GastoService servico = new GastoService(gastos, categorias, contas, eventos);

    private final GastoId id = new GastoId(UUID.randomUUID());

    GastoServiceTest() {
        when(categorias.exigirAtiva(MERCADO.id())).thenReturn(MERCADO);
        when(categorias.exigirAtiva(LAZER.id())).thenReturn(LAZER);
        when(categorias.buscar(MERCADO.id())).thenReturn(Optional.of(MERCADO));
        when(categorias.buscar(LAZER.id())).thenReturn(Optional.of(LAZER));
    }

    @Test
    void registrar_lanca_a_saida_com_o_id_do_gasto_grava_e_publica_o_evento() {
        when(gastos.buscar(id)).thenReturn(Optional.empty());
        when(gastos.inserirSeAusente(any())).thenReturn(true);

        var resultado = servico.registrar(id, novo("120"));

        assertThat(resultado.criado()).isTrue();
        assertThat(resultado.gasto().categoriaNome()).isEqualTo("Mercado");
        var ordem = inOrder(contas, gastos, eventos);
        ordem.verify(contas).registrarSaida(NUBANK, id.valor(), Dinheiro.de("120"), DIA);
        ordem.verify(gastos).inserirSeAusente(gasto("120"));
        ordem.verify(eventos).publishEvent(new GastoLancado(id, DIA, Dinheiro.de("120"), MERCADO.id()));
    }

    @Test
    void falha_na_fachada_nao_grava_nem_publica() {
        when(gastos.buscar(id)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("conta inativa")).when(contas)
                .registrarSaida(any(), any(), any(), any());

        assertThatThrownBy(() -> servico.registrar(id, novo("120"))).isInstanceOf(IllegalStateException.class);

        verify(gastos, never()).inserirSeAusente(any());
        verifyNoInteractions(eventos);
    }

    @Test
    void categoria_inativa_ou_inexistente_para_antes_da_fachada() {
        when(gastos.buscar(id)).thenReturn(Optional.empty());
        when(categorias.exigirAtiva(MERCADO.id())).thenThrow(new CategoriaInativaException(MERCADO.id()));

        assertThatThrownBy(() -> servico.registrar(id, novo("120"))).isInstanceOf(CategoriaInativaException.class);

        verifyNoInteractions(contas);
    }

    @Test
    void reenvio_identico_devolve_o_existente_sem_chamar_a_fachada() {
        when(gastos.buscar(id)).thenReturn(Optional.of(vivo(gasto("120"))));

        var resultado = servico.registrar(id, novo("120"));

        assertThat(resultado.criado()).isFalse();
        assertThat(resultado.gasto().gasto()).isEqualTo(gasto("120"));
        verifyNoInteractions(contas, eventos);
    }

    @Test
    void reenvio_diferente_e_409_e_id_excluido_tambem() {
        when(gastos.buscar(id)).thenReturn(Optional.of(vivo(gasto("120"))));
        assertThatThrownBy(() -> servico.registrar(id, novo("121"))).isInstanceOf(GastoDivergenteException.class);

        when(gastos.buscar(id)).thenReturn(Optional.of(new GastoRepository.Registro(gasto("120"), "Mercado", true)));
        assertThatThrownBy(() -> servico.registrar(id, novo("120"))).isInstanceOf(GastoExcluidoException.class);

        verifyNoInteractions(contas);
    }

    @Test
    void corrida_entre_dois_envios_confere_com_o_que_o_outro_gravou() {
        when(gastos.buscar(id)).thenReturn(Optional.empty(), Optional.of(vivo(gasto("120"))));
        when(gastos.inserirSeAusente(any())).thenReturn(false);

        var resultado = servico.registrar(id, novo("120"));

        assertThat(resultado.criado()).isFalse();
        verifyNoInteractions(eventos);
    }

    @Test
    void editar_valor_data_e_conta_altera_o_lancamento_com_os_novos_valores() {
        when(gastos.buscar(id)).thenReturn(Optional.of(vivo(gasto("120"))));

        var alterado = servico.alterar(id, new AlteracaoGasto(DIA.plusDays(1), null, Dinheiro.de("150"), null,
                INTER));

        verify(contas).alterarLancamento(id.valor(), INTER, Dinheiro.de("150"), DIA.plusDays(1));
        var esperado = new Gasto(id, DIA.plusDays(1), "Feira", Dinheiro.de("150"), MERCADO.id(),
                new FormaPagamento.AVista(INTER));
        verify(gastos).alterar(esperado);
        assertThat(alterado.gasto()).isEqualTo(esperado);
    }

    @Test
    void editar_so_descricao_ou_categoria_nao_mexe_na_conta() {
        when(gastos.buscar(id)).thenReturn(Optional.of(vivo(gasto("120"))));

        var alterado = servico.alterar(id, new AlteracaoGasto(null, "Feira do sábado", null, LAZER.id(), null));

        verify(contas, never()).alterarLancamento(any(), any(), any(), any());
        assertThat(alterado.gasto().descricao()).isEqualTo("Feira do sábado");
        assertThat(alterado.categoriaNome()).isEqualTo("Lazer");
    }

    @Test
    void editar_com_categoria_inativa_e_recusado() {
        when(gastos.buscar(id)).thenReturn(Optional.of(vivo(gasto("120"))));
        when(categorias.exigirAtiva(LAZER.id())).thenThrow(new CategoriaInativaException(LAZER.id()));

        assertThatThrownBy(() -> servico.alterar(id, new AlteracaoGasto(null, null, null, LAZER.id(), null)))
                .isInstanceOf(CategoriaInativaException.class);
        verify(gastos, never()).alterar(any());
    }

    @Test
    void editar_gasto_inexistente_ou_excluido_e_404() {
        when(gastos.buscar(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servico.alterar(id, new AlteracaoGasto(null, "x", null, null, null)))
                .isInstanceOf(GastoNaoEncontradoException.class);

        when(gastos.buscar(id)).thenReturn(Optional.of(new GastoRepository.Registro(gasto("120"), "Mercado", true)));
        assertThatThrownBy(() -> servico.alterar(id, new AlteracaoGasto(null, "x", null, null, null)))
                .isInstanceOf(GastoNaoEncontradoException.class);
    }

    @Test
    void gasto_em_folha_so_muda_a_categoria() {
        when(gastos.buscar(id)).thenReturn(Optional.of(vivo(emFolha())));

        for (var proibida : List.of(
                new AlteracaoGasto(DIA, null, null, null, null),
                new AlteracaoGasto(null, "Outro", null, null, null),
                new AlteracaoGasto(null, null, Dinheiro.de("1"), null, null),
                new AlteracaoGasto(null, null, null, null, NUBANK))) {
            assertThatThrownBy(() -> servico.alterar(id, proibida)).isInstanceOf(GastoDoHoleriteException.class);
        }

        var alterado = servico.alterar(id, new AlteracaoGasto(null, null, null, LAZER.id(), null));

        assertThat(alterado.gasto().categoria()).isEqualTo(LAZER.id());
        verifyNoInteractions(contas);
    }

    @Test
    void excluir_marca_o_gasto_e_a_saida_e_e_idempotente() {
        when(gastos.buscar(id)).thenReturn(Optional.of(vivo(gasto("120"))));
        servico.excluir(id);
        verify(gastos).excluir(id);
        verify(contas).excluirLancamento(id.valor());

        when(gastos.buscar(id)).thenReturn(Optional.of(new GastoRepository.Registro(gasto("120"), "Mercado", true)));
        when(gastos.buscar(new GastoId(UUID.randomUUID()))).thenReturn(Optional.empty());
        assertThatCode(() -> servico.excluir(id)).doesNotThrowAnyException();
        assertThatCode(() -> servico.excluir(new GastoId(UUID.randomUUID()))).doesNotThrowAnyException();
        verify(gastos).excluir(id);
    }

    @Test
    void gasto_em_folha_nao_se_exclui_nem_se_restaura_pela_api() {
        when(gastos.buscar(id)).thenReturn(Optional.of(vivo(emFolha())));

        assertThatThrownBy(() -> servico.excluir(id)).isInstanceOf(GastoDoHoleriteException.class);
        assertThatThrownBy(() -> servico.restaurar(id)).isInstanceOf(GastoDoHoleriteException.class);
        verifyNoInteractions(contas);
    }

    @Test
    void restaurar_desfaz_as_duas_exclusoes_e_restaurar_o_vivo_nao_muda_nada() {
        when(gastos.buscar(id)).thenReturn(Optional.of(new GastoRepository.Registro(gasto("120"), "Mercado", true)));
        assertThat(servico.restaurar(id).gasto()).isEqualTo(gasto("120"));
        verify(gastos).restaurar(id);
        verify(contas).restaurarLancamento(id.valor());

        when(gastos.buscar(id)).thenReturn(Optional.of(vivo(gasto("120"))));
        servico.restaurar(id);
        verify(gastos).restaurar(id);

        when(gastos.buscar(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servico.restaurar(id)).isInstanceOf(GastoNaoEncontradoException.class);
    }

    @Test
    void listar_repassa_competencia_e_categoria() {
        var novembro = Competencia.de("2026-11");
        when(gastos.listar(novembro, MERCADO.id())).thenReturn(List.of(new GastoComCategoria(gasto("1"), "Mercado")));

        assertThat(servico.listar(novembro, MERCADO.id())).hasSize(1);
    }

    private NovoGasto novo(String valor) {
        return new NovoGasto(DIA, "  Feira ", Dinheiro.de(valor), MERCADO.id(), NUBANK);
    }

    private Gasto gasto(String valor) {
        return new Gasto(id, DIA, "Feira", Dinheiro.de(valor), MERCADO.id(), new FormaPagamento.AVista(NUBANK));
    }

    private Gasto emFolha() {
        return new Gasto(id, DIA, "Vale-Transporte", Dinheiro.de("180"), MERCADO.id(),
                new FormaPagamento.DescontoEmFolha(new HoleriteId(UUID.randomUUID())));
    }

    private static GastoRepository.Registro vivo(Gasto gasto) {
        return new GastoRepository.Registro(gasto, "Mercado", false);
    }
}
