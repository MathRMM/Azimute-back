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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** RF20–RF24, RN02, RN04, RN09, P4, RF52: o serviço de ganhos avulsos sem banco, com a fachada de contas falsa. */
class GanhoServiceTest {

    private static final Competencia OUTUBRO = Competencia.de("2026-10");
    private static final LocalDate DIA = LocalDate.of(2026, 11, 5);
    private static final ContaId NUBANK = new ContaId(UUID.randomUUID());
    private static final ContaId INTER = new ContaId(UUID.randomUUID());

    private final GanhoRepository ganhos = mock(GanhoRepository.class);
    private final ContasFacade contas = mock(ContasFacade.class);
    private final ApplicationEventPublisher eventos = mock(ApplicationEventPublisher.class);
    private final GanhoService servico = new GanhoService(ganhos, contas, eventos);

    private final GanhoId id = new GanhoId(UUID.randomUUID());

    @Test
    void registrar_lanca_a_entrada_com_a_referencia_do_recebimento_grava_e_publica() {
        when(ganhos.buscar(id)).thenReturn(Optional.empty());
        when(ganhos.inserirSeAusente(any())).thenReturn(true);

        var resultado = servico.registrar(id, novo(TipoGanho.FREELA, "1000", "850"));

        assertThat(resultado.criado()).isTrue();
        assertThat(resultado.ganho()).isEqualTo(new GanhoComRecebimentos(ganho("1000", "850"),
                List.of(recebimento("850", NUBANK, DIA))));
        var ordem = inOrder(contas, ganhos, eventos);
        ordem.verify(contas).registrarEntrada(NUBANK, id.valor(), Dinheiro.de("850"), DIA);
        ordem.verify(ganhos).inserirSeAusente(ganho("1000", "850"));
        ordem.verify(ganhos).inserirRecebimento(recebimento("850", NUBANK, DIA));
        ordem.verify(eventos).publishEvent(new GanhoRegistrado(id, OUTUBRO, Dinheiro.de("850")));
    }

    @Test
    void liquido_maior_que_bruto_e_salario_param_antes_da_fachada() {
        when(ganhos.buscar(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.registrar(id, novo(TipoGanho.FREELA, "100", "100.01")))
                .isInstanceOf(LiquidoMaiorQueBrutoException.class);
        assertThatThrownBy(() -> servico.registrar(id, novo(TipoGanho.SALARIO, "5000", "3800")))
                .isInstanceOfSatisfying(SalarioSoPeloHoleriteException.class,
                        e -> assertThat(e.codigo()).isEqualTo("SALARIO_SO_PELO_HOLERITE"));

        verifyNoInteractions(contas, eventos);
    }

    @Test
    void reenvio_identico_devolve_o_existente_e_diferente_ou_excluido_e_409() {
        when(ganhos.buscar(id)).thenReturn(Optional.of(vivo()));

        var resultado = servico.registrar(id, novo(TipoGanho.FREELA, "1000", "850"));
        assertThat(resultado.criado()).isFalse();
        assertThatThrownBy(() -> servico.registrar(id, novo(TipoGanho.FREELA, "1000", "851")))
                .isInstanceOf(GanhoDivergenteException.class);
        assertThatThrownBy(() -> servico.registrar(id, new NovoGanho(OUTUBRO, "Freela", TipoGanho.FREELA,
                Dinheiro.de("1000"), Dinheiro.de("850"), INTER, DIA)))
                .isInstanceOf(GanhoDivergenteException.class);

        when(ganhos.buscar(id)).thenReturn(Optional.of(new GanhoRepository.Registro(ganho("1000", "850"),
                List.of(recebimento("850", NUBANK, DIA)), true)));
        assertThatThrownBy(() -> servico.registrar(id, novo(TipoGanho.FREELA, "1000", "850")))
                .isInstanceOf(GanhoExcluidoException.class);

        verifyNoInteractions(contas, eventos);
    }

    @Test
    void corrida_entre_dois_envios_confere_com_o_que_o_outro_gravou() {
        when(ganhos.buscar(id)).thenReturn(Optional.empty(), Optional.of(vivo()));
        when(ganhos.inserirSeAusente(any())).thenReturn(false);

        assertThat(servico.registrar(id, novo(TipoGanho.FREELA, "1000", "850")).criado()).isFalse();
        verify(ganhos, never()).inserirRecebimento(any());
        verifyNoInteractions(eventos);
    }

    @Test
    void editar_liquido_conta_e_data_ajusta_a_entrada() {
        when(ganhos.buscar(id)).thenReturn(Optional.of(vivo()));

        var alterado = servico.alterar(id, new AlteracaoGanho(null, null, null, null, Dinheiro.de("900"), INTER,
                DIA.plusDays(1)));

        verify(contas).alterarLancamento(id.valor(), INTER, Dinheiro.de("900"), DIA.plusDays(1));
        verify(ganhos).alterar(ganho("1000", "900"));
        verify(ganhos).alterarRecebimento(recebimento("900", INTER, DIA.plusDays(1)));
        assertThat(alterado.ganho().liquido()).isEqualTo(Dinheiro.de("900"));
    }

    @Test
    void editar_so_descricao_competencia_tipo_ou_bruto_nao_mexe_na_conta() {
        when(ganhos.buscar(id)).thenReturn(Optional.of(vivo()));

        var alterado = servico.alterar(id, new AlteracaoGanho(Competencia.de("2026-11"), "Projeto X",
                TipoGanho.OUTRO, Dinheiro.de("1200"), null, null, null));

        verify(contas, never()).alterarLancamento(any(), any(), any(), any());
        assertThat(alterado.ganho()).isEqualTo(new Ganho(id, Competencia.de("2026-11"), "Projeto X",
                TipoGanho.OUTRO, Dinheiro.de("1200"), Dinheiro.de("850"), null));
        assertThat(alterado.recebimentos().getFirst().competencia()).isEqualTo(Competencia.de("2026-11"));
    }

    @Test
    void editar_para_liquido_acima_do_bruto_ou_para_salario_e_recusado() {
        when(ganhos.buscar(id)).thenReturn(Optional.of(vivo()));

        assertThatThrownBy(() -> servico.alterar(id, new AlteracaoGanho(null, null, null, Dinheiro.de("800"), null,
                null, null))).isInstanceOf(LiquidoMaiorQueBrutoException.class);
        assertThatThrownBy(() -> servico.alterar(id, new AlteracaoGanho(null, null, TipoGanho.SALARIO, null, null,
                null, null))).isInstanceOf(SalarioSoPeloHoleriteException.class);
        verifyNoInteractions(contas);
        verify(ganhos, never()).alterar(any());
    }

    @Test
    void editar_inexistente_ou_excluido_e_404_e_ganho_do_holerite_e_422() {
        var alteracao = new AlteracaoGanho(null, "x", null, null, null, null, null);
        when(ganhos.buscar(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servico.alterar(id, alteracao)).isInstanceOf(GanhoNaoEncontradoException.class);

        when(ganhos.buscar(id)).thenReturn(Optional.of(new GanhoRepository.Registro(ganho("1000", "850"),
                List.of(recebimento("850", NUBANK, DIA)), true)));
        assertThatThrownBy(() -> servico.alterar(id, alteracao)).isInstanceOf(GanhoNaoEncontradoException.class);

        when(ganhos.buscar(id)).thenReturn(Optional.of(doHolerite()));
        assertThatThrownBy(() -> servico.alterar(id, alteracao)).isInstanceOf(GanhoDoHoleriteException.class);
    }

    @Test
    void excluir_marca_o_ganho_e_a_entrada_e_e_idempotente() {
        when(ganhos.buscar(id)).thenReturn(Optional.of(vivo()));
        servico.excluir(id);
        verify(ganhos).excluir(id);
        verify(contas).excluirLancamento(id.valor());

        when(ganhos.buscar(id)).thenReturn(Optional.of(new GanhoRepository.Registro(ganho("1000", "850"),
                List.of(recebimento("850", NUBANK, DIA)), true)));
        assertThatCode(() -> servico.excluir(id)).doesNotThrowAnyException();
        assertThatCode(() -> servico.excluir(new GanhoId(UUID.randomUUID()))).doesNotThrowAnyException();
        verify(ganhos).excluir(id);
    }

    @Test
    void restaurar_desfaz_as_duas_exclusoes() {
        when(ganhos.buscar(id)).thenReturn(Optional.of(new GanhoRepository.Registro(ganho("1000", "850"),
                List.of(recebimento("850", NUBANK, DIA)), true)));
        assertThat(servico.restaurar(id).ganho()).isEqualTo(ganho("1000", "850"));
        verify(ganhos).restaurar(id);
        verify(contas).restaurarLancamento(id.valor());

        when(ganhos.buscar(id)).thenReturn(Optional.of(vivo()));
        servico.restaurar(id);
        verify(ganhos).restaurar(id);

        when(ganhos.buscar(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servico.restaurar(id)).isInstanceOf(GanhoNaoEncontradoException.class);
    }

    @Test
    void ganho_do_holerite_nao_se_exclui_nem_se_restaura_pela_api() {
        when(ganhos.buscar(id)).thenReturn(Optional.of(doHolerite()));

        assertThatThrownBy(() -> servico.excluir(id)).isInstanceOf(GanhoDoHoleriteException.class);
        assertThatThrownBy(() -> servico.restaurar(id)).isInstanceOf(GanhoDoHoleriteException.class);
        verifyNoInteractions(contas);
    }

    @Test
    void listar_repassa_a_competencia() {
        when(ganhos.listar(OUTUBRO)).thenReturn(List.of(new GanhoComRecebimentos(ganho("1", "1"), List.of())));

        assertThat(servico.listar(OUTUBRO)).hasSize(1);
    }

    private NovoGanho novo(TipoGanho tipo, String bruto, String liquido) {
        return new NovoGanho(OUTUBRO, " Freela ", tipo, Dinheiro.de(bruto), Dinheiro.de(liquido), NUBANK, DIA);
    }

    private Ganho ganho(String bruto, String liquido) {
        return new Ganho(id, OUTUBRO, "Freela", TipoGanho.FREELA, Dinheiro.de(bruto), Dinheiro.de(liquido), null);
    }

    private Recebimento recebimento(String valor, ContaId conta, LocalDate data) {
        return new Recebimento(id.valor(), id, OUTUBRO, Recebimento.Papel.AVULSO, Dinheiro.de(valor), data, conta,
                false);
    }

    private GanhoRepository.Registro vivo() {
        return new GanhoRepository.Registro(ganho("1000", "850"), List.of(recebimento("850", NUBANK, DIA)), false);
    }

    private GanhoRepository.Registro doHolerite() {
        var salario = new Ganho(id, OUTUBRO, "Salário", TipoGanho.SALARIO, Dinheiro.de("5000"), Dinheiro.de("3800"),
                new HoleriteId(UUID.randomUUID()));
        return new GanhoRepository.Registro(salario, List.of(), false);
    }
}
