package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static br.dev.azimute.calculadora.historico.MontadorDeSimulacao.estimativa;
import static br.dev.azimute.calculadora.historico.MontadorDeSimulacao.proposta;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** RF20–RF23, RN10, RN11, D2–D4, T3: as regras do histórico, com repositório e conferência falsos. */
class HistoricoServiceTest {

    private final SimulacaoRepository repositorio = mock(SimulacaoRepository.class);
    private final ConferenciaDeSnapshot conferencia = mock(ConferenciaDeSnapshot.class);
    private final HistoricoService servico = new HistoricoService(repositorio, conferencia);
    private final SimulacaoId id = new SimulacaoId(UUID.randomUUID());

    // --- salvar: validação de valores (RN10) ------------------------------------------------------------------

    @Test
    void todos_os_valores_invalidos_sao_apontados_de_uma_vez_antes_de_tocar_o_banco() {
        var dados = proposta().com(m -> {
            m.bem = "   ";
            m.valorBem = "0";
            m.entrada = "-1";
            m.parcela = "0";
            m.prazo = 0;
            m.taxaMensal = "-0.01";
            m.taxaAnual = "-0.1";
        }).montar();

        assertThatThrownBy(() -> servico.salvar(id, dados))
                .isInstanceOfSatisfying(DadosInvalidosException.class, e -> assertThat(campos(e))
                        .containsOnlyKeys("bem", "valorBem", "entrada", "parcela", "prazo", "taxaMensal", "taxaAnual")
                        .containsEntry("entrada", "não pode ser negativa"));
        verifyNoInteractions(repositorio, conferencia);
    }

    @Test
    void entrada_igual_ao_valor_do_bem_e_invalida() {
        var dados = proposta().com(m -> m.entrada = "1000").montar();

        assertThatThrownBy(() -> servico.salvar(id, dados))
                .isInstanceOfSatisfying(DadosInvalidosException.class, e -> assertThat(campos(e))
                        .containsExactlyEntriesOf(Map.of("entrada", "deve ser menor que o valor do bem")));
    }

    // --- salvar: âncora (RN11) --------------------------------------------------------------------------------

    @Test
    void proposta_com_ancora_e_recusada() {
        var dados = proposta().com(m -> m.ancoraId = new SimulacaoId(UUID.randomUUID())).montar();

        assertThatThrownBy(() -> servico.salvar(id, dados))
                .isInstanceOf(AncoraInvalidaException.class).hasMessage("Proposta não tem âncora");
    }

    @Test
    void estimativa_sem_ancora_e_recusada() {
        assertThatThrownBy(() -> servico.salvar(id, estimativa(null).montar()))
                .isInstanceOf(AncoraInvalidaException.class)
                .hasMessage("Estimativa precisa indicar a proposta de onde a taxa veio");
    }

    @Test
    void estimativa_com_ancora_inexistente_e_recusada() {
        var ancora = new SimulacaoId(UUID.randomUUID());
        when(repositorio.buscar(ancora)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.salvar(id, estimativa(ancora).montar()))
                .isInstanceOf(AncoraInvalidaException.class).hasMessage("Proposta âncora não encontrada");
    }

    @Test
    void estimativa_ancorada_em_outra_estimativa_e_recusada() {
        var ancora = new SimulacaoId(UUID.randomUUID());
        when(repositorio.buscar(ancora)).thenReturn(Optional.of(estimativa(id).salva(ancora)));

        assertThatThrownBy(() -> servico.salvar(id, estimativa(ancora).montar()))
                .isInstanceOf(AncoraInvalidaException.class)
                .hasMessage("A âncora de uma estimativa precisa ser uma proposta");
    }

    @Test
    void estimativa_valida_e_conferida_contra_a_propria_ancora() {
        var ancoraId = new SimulacaoId(UUID.randomUUID());
        var ancora = proposta().salva(ancoraId);
        var dados = estimativa(ancoraId).montar();
        when(repositorio.buscar(ancoraId)).thenReturn(Optional.of(ancora));
        when(repositorio.inserirSeAusente(id, dados)).thenReturn(true);
        when(repositorio.buscar(id)).thenReturn(Optional.of(new SimulacaoSalva(id, dados, StatusSimulacao.RECEBIDA, Instant.now())));

        servico.salvar(id, dados);

        verify(conferencia).conferir(dados, ancora);
    }

    @Test
    void snapshot_divergente_impede_a_gravacao() {
        var dados = proposta().montar();
        doThrow(new SnapshotDivergenteException(Map.of())).when(conferencia).conferir(dados, null);

        assertThatThrownBy(() -> servico.salvar(id, dados)).isInstanceOf(SnapshotDivergenteException.class);
        verify(repositorio, never()).inserirSeAusente(any(), any());
    }

    // --- salvar: idempotência (RF20, D2) ----------------------------------------------------------------------

    @Test
    void id_novo_e_gravado_e_devolvido() {
        var dados = proposta().montar();
        var salva = new SimulacaoSalva(id, dados, StatusSimulacao.RECEBIDA, Instant.now());
        when(repositorio.inserirSeAusente(id, dados)).thenReturn(true);
        when(repositorio.buscar(id)).thenReturn(Optional.of(salva));

        assertThat(servico.salvar(id, dados)).isSameAs(salva);
    }

    @Test
    void reenvio_com_os_mesmos_valores_devolve_o_existente_mesmo_com_outra_observacao() {
        var existente = proposta().com(m -> m.observacao = "primeira visita").salva(id);
        when(repositorio.inserirSeAusente(any(), any())).thenReturn(false);
        when(repositorio.buscar(id)).thenReturn(Optional.of(existente));

        var reenvio = proposta().com(m -> m.observacao = "outra anotação").montar();

        assertThat(servico.salvar(id, reenvio)).isSameAs(existente);
    }

    @Test
    void reenvio_com_valores_diferentes_e_conflito() {
        when(repositorio.inserirSeAusente(any(), any())).thenReturn(false);
        when(repositorio.buscar(id)).thenReturn(Optional.of(proposta().salva(id)));

        var divergente = proposta().com(m -> m.bem = "Moto").montar();

        assertThatThrownBy(() -> servico.salvar(id, divergente))
                .isInstanceOfSatisfying(SimulacaoDivergenteException.class,
                        e -> assertThat(e.detalhes()).containsEntry("id", id.toString()));
    }

    // --- listar e anotar (RF21, RF23) -------------------------------------------------------------------------

    @Test
    void listar_sem_filtro_ou_com_filtro_em_branco_lista_tudo() {
        servico.listar(null);
        servico.listar("  ");
        servico.listar("Carro");

        verify(repositorio, org.mockito.Mockito.times(2)).listar(null);
        verify(repositorio).listar("Carro");
    }

    @Test
    void anotar_simulacao_inexistente_e_404_e_nao_escreve() {
        when(repositorio.buscar(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.anotar(id, new Anotacao(true, "x", null)))
                .isInstanceOf(SimulacaoNaoEncontradaException.class);
        verify(repositorio, never()).anotar(any(), any());
    }

    @Test
    void anotar_grava_e_devolve_o_estado_relido_do_banco() {
        var anotacao = new Anotacao(false, null, StatusSimulacao.ACEITA);
        var antes = proposta().salva(id);
        var depois = new SimulacaoSalva(id, antes.dados(), StatusSimulacao.ACEITA, antes.sincronizadaEm());
        when(repositorio.buscar(id)).thenReturn(Optional.of(antes), Optional.of(depois));

        assertThat(servico.anotar(id, anotacao)).isSameAs(depois);
        verify(repositorio).anotar(id, anotacao);
    }

    @Test
    void anotar_simulacao_excluida_no_meio_da_operacao_e_404() {
        when(repositorio.buscar(id)).thenReturn(Optional.of(proposta().salva(id)), Optional.empty());

        assertThatThrownBy(() -> servico.anotar(id, new Anotacao(true, null, null)))
                .isInstanceOf(SimulacaoNaoEncontradaException.class);
    }

    // --- excluir (T3, D3) -------------------------------------------------------------------------------------

    @Test
    void excluir_proposta_sem_estimativas_exclui() {
        when(repositorio.estimativasAncoradasEm(id)).thenReturn(List.of());

        servico.excluir(id);

        verify(repositorio).excluir(id);
    }

    @Test
    void excluir_proposta_que_e_ancora_e_conflito_e_nao_exclui() {
        when(repositorio.estimativasAncoradasEm(id)).thenReturn(List.of("est-1", "est-2"));

        assertThatThrownBy(() -> servico.excluir(id))
                .isInstanceOfSatisfying(SimulacaoEAncoraException.class,
                        e -> assertThat(e.detalhes()).containsEntry("estimativas", List.of("est-1", "est-2")));
        verify(repositorio, never()).excluir(any());
    }

    @Test
    void estimativa_gravada_entre_a_verificacao_e_a_exclusao_vira_conflito() {
        // A exclusão lógica só acontece se não houver estimativa viva (NOT EXISTS no UPDATE): nada foi excluído.
        when(repositorio.estimativasAncoradasEm(id)).thenReturn(List.of(), List.of("est-nova"));
        when(repositorio.excluir(id)).thenReturn(false);

        assertThatThrownBy(() -> servico.excluir(id))
                .isInstanceOfSatisfying(SimulacaoEAncoraException.class,
                        e -> assertThat(e.detalhes()).containsEntry("estimativas", List.of("est-nova")));
    }

    @Test
    void excluir_o_que_ja_foi_excluido_nao_e_erro() {
        when(repositorio.estimativasAncoradasEm(id)).thenReturn(List.of());
        when(repositorio.excluir(id)).thenReturn(false);

        servico.excluir(id);

        verify(repositorio).excluir(id);
    }

    // --- restaurar e reenvio de excluída (RF41) -----------------------------------------------------------------

    @Test
    void reenvio_de_simulacao_excluida_e_conflito_e_nao_a_ressuscita() {
        when(repositorio.inserirSeAusente(any(), any())).thenReturn(false);
        when(repositorio.buscar(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.salvar(id, proposta().montar()))
                .isInstanceOfSatisfying(SimulacaoExcluidaException.class,
                        e -> assertThat(e.codigo()).isEqualTo("SIMULACAO_EXCLUIDA"));
    }

    @Test
    void restaurar_excluida_devolve_a_simulacao() {
        var salva = proposta().salva(id);
        when(repositorio.restaurar(id)).thenReturn(true);
        when(repositorio.buscar(id)).thenReturn(Optional.of(salva));

        assertThat(servico.restaurar(id)).isEqualTo(salva);
    }

    @Test
    void restaurar_viva_nao_muda_nada_e_inexistente_e_404() {
        var salva = proposta().salva(id);
        when(repositorio.restaurar(id)).thenReturn(false);
        when(repositorio.buscar(id)).thenReturn(Optional.of(salva));
        assertThat(servico.restaurar(id)).isEqualTo(salva);

        var outro = new SimulacaoId(java.util.UUID.randomUUID());
        when(repositorio.restaurar(outro)).thenReturn(false);
        when(repositorio.buscar(outro)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servico.restaurar(outro)).isInstanceOf(SimulacaoNaoEncontradaException.class);
    }

    // --- comparar (RF22, D4) ----------------------------------------------------------------------------------

    @Test
    void comparar_exige_o_bem() {
        assertThatThrownBy(() -> servico.comparar(null, false)).isInstanceOf(DadosInvalidosException.class);
        assertThatThrownBy(() -> servico.comparar(" ", false)).isInstanceOf(DadosInvalidosException.class);
        verifyNoInteractions(repositorio);
    }

    @Test
    void bem_sem_propostas_devolve_lista_vazia_e_economia_zero() {
        when(repositorio.listar(" Carro ")).thenReturn(List.of());

        var comparacao = servico.comparar(" Carro ", false);

        assertThat(comparacao.bem()).isEqualTo("Carro");
        assertThat(comparacao.propostas()).isEmpty();
        assertThat(comparacao.melhor()).isNull();
        assertThat(comparacao.economiaEntreMelhorEPior()).isEqualTo(Dinheiro.ZERO);
    }

    @Test
    void ordena_pela_menor_taxa_desempata_pela_data_e_ignora_estimativas_por_padrao() {
        var cara = salva("0.02", LocalDate.of(2026, 11, 1), "2026-11-01T12:00:00Z", "40", OrigemSimulacao.PROPOSTA);
        var baratoAntiga = salva("0.01", LocalDate.of(2026, 10, 1), "2026-11-01T12:00:00Z", "15", OrigemSimulacao.PROPOSTA);
        var baratoRecente = salva("0.01", LocalDate.of(2026, 11, 1), "2026-11-01T12:00:00Z", "10", OrigemSimulacao.PROPOSTA);
        var estimativa = salva("0.005", LocalDate.of(2026, 11, 1), "2026-11-01T12:00:00Z", "5", OrigemSimulacao.ESTIMATIVA);
        when(repositorio.listar("Carro")).thenReturn(List.of(cara, estimativa, baratoAntiga, baratoRecente));

        var semEstimativas = servico.comparar("Carro", false);
        var comEstimativas = servico.comparar("Carro", true);

        assertThat(semEstimativas.propostas()).containsExactly(baratoRecente, baratoAntiga, cara);
        assertThat(semEstimativas.melhor()).isSameAs(baratoRecente);
        assertThat(semEstimativas.economiaEntreMelhorEPior()).isEqualTo(Dinheiro.de("30"));
        assertThat(comEstimativas.propostas()).containsExactly(estimativa, baratoRecente, baratoAntiga, cara);
    }

    @Test
    void mesma_taxa_e_mesma_data_desempata_pelo_calculo_mais_recente() {
        var primeiro = salva("0.01", LocalDate.of(2026, 11, 1), "2026-11-01T09:00:00Z", "10", OrigemSimulacao.PROPOSTA);
        var segundo = salva("0.01", LocalDate.of(2026, 11, 1), "2026-11-01T18:00:00Z", "10", OrigemSimulacao.PROPOSTA);
        when(repositorio.listar("Carro")).thenReturn(List.of(primeiro, segundo));

        assertThat(servico.comparar("Carro", false).propostas()).containsExactly(segundo, primeiro);
    }

    private static SimulacaoSalva salva(String taxa, LocalDate data, String calculadaEm, String juros,
                                        OrigemSimulacao origem) {
        return proposta().com(m -> {
            m.taxaMensal = taxa;
            m.data = data;
            m.calculadaEm = Instant.parse(calculadaEm);
            m.totalJuros = juros;
            m.origem = origem;
        }).salva();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> campos(DadosInvalidosException e) {
        return (Map<String, Object>) e.detalhes().get("campos");
    }
}
