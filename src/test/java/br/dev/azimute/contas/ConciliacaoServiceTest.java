package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

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
import static org.mockito.Mockito.when;

/** RF20, RF21, RN07, RN08, P3, T4: divergência, ordem das datas e reenvio, sem banco. */
class ConciliacaoServiceTest {

    /** 14/11 às 12h em São Paulo. */
    private static final Instant AGORA = Instant.parse("2026-11-14T15:00:00Z");
    private static final LocalDate HOJE = LocalDate.of(2026, 11, 14);
    private static final LocalDate BASE = LocalDate.of(2026, 11, 1);

    private final ContaRepository contas = mock(ContaRepository.class);
    private final ConciliacaoRepository conciliacoes = mock(ConciliacaoRepository.class);
    private final ContaService saldos = mock(ContaService.class);
    private final ConciliacaoService servico = new ConciliacaoService(contas, conciliacoes, saldos,
            Clock.fixed(AGORA, ZoneId.of("UTC")));

    private final ContaId nubank = new ContaId(UUID.randomUUID());

    @Test
    void divergencia_e_o_real_menos_o_calculado_e_a_conciliacao_vira_a_nova_base() {
        prepararConta(true);
        when(saldos.saldoEm(nubank, HOJE)).thenReturn(Dinheiro.de("800"));
        var novaId = new ConciliacaoId(UUID.randomUUID());
        when(conciliacoes.inserir(any(), any(), any(), any(), any(), any())).thenReturn(novaId);

        var resultado = servico.conciliar(nubank, HOJE, Dinheiro.de("770"), "  pix esquecido ");

        assertThat(resultado.criada()).isTrue();
        assertThat(resultado.conciliacao()).isEqualTo(new Conciliacao(novaId, nubank, HOJE, Dinheiro.de("770"),
                Dinheiro.de("800"), Dinheiro.de("-30"), "pix esquecido"));
        verify(conciliacoes).inserir(nubank, HOJE, Dinheiro.de("770"), Dinheiro.de("800"), Dinheiro.de("-30"),
                "pix esquecido");
    }

    @Test
    void justificativa_em_branco_vira_nula() {
        prepararConta(true);
        when(saldos.saldoEm(nubank, HOJE)).thenReturn(Dinheiro.de("100"));
        when(conciliacoes.inserir(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ConciliacaoId(UUID.randomUUID()));

        assertThat(servico.conciliar(nubank, HOJE, Dinheiro.de("100"), "   ").conciliacao().justificativa()).isNull();
    }

    @Test
    void data_igual_a_da_ultima_com_outro_valor_esta_fora_de_ordem() {
        prepararConta(true);

        assertThatThrownBy(() -> servico.conciliar(nubank, BASE, Dinheiro.de("999"), null))
                .isInstanceOfSatisfying(ConciliacaoForaDeOrdemException.class, e -> {
                    assertThat(e.codigo()).isEqualTo("CONCILIACAO_FORA_DE_ORDEM");
                    assertThat(e.detalhes()).containsEntry("ultimaConciliacao", "2026-11-01");
                });
        verify(conciliacoes, never()).inserir(any(), any(), any(), any(), any(), any());
    }

    @Test
    void data_anterior_a_da_ultima_esta_fora_de_ordem() {
        prepararConta(true);

        assertThatThrownBy(() -> servico.conciliar(nubank, BASE.minusDays(1), Dinheiro.de("1000"), null))
                .isInstanceOf(ConciliacaoForaDeOrdemException.class);
    }

    @Test
    void reenvio_identico_da_ultima_devolve_a_existente_sem_gravar() {
        var ultima = prepararConta(true);

        var resultado = servico.conciliar(nubank, BASE, Dinheiro.de("1000"), null);

        assertThat(resultado.criada()).isFalse();
        assertThat(resultado.conciliacao()).isEqualTo(ultima);
        verify(conciliacoes, never()).inserir(any(), any(), any(), any(), any(), any());
    }

    @Test
    void data_futura_e_recusada() {
        prepararConta(true);

        assertThatThrownBy(() -> servico.conciliar(nubank, HOJE.plusDays(1), Dinheiro.de("1"), null))
                .isInstanceOfSatisfying(DataFuturaException.class,
                        e -> assertThat(e.codigo()).isEqualTo("DATA_FUTURA"));
    }

    @Test
    void conta_inativa_e_conta_inexistente() {
        prepararConta(false);
        var inexistente = new ContaId(UUID.randomUUID());
        when(contas.buscar(inexistente)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.conciliar(nubank, HOJE, Dinheiro.de("1"), null))
                .isInstanceOf(ContaInativaException.class);
        assertThatThrownBy(() -> servico.conciliar(inexistente, HOJE, Dinheiro.de("1"), null))
                .isInstanceOf(ContaNaoEncontradaException.class);
        assertThatThrownBy(() -> servico.historico(inexistente)).isInstanceOf(ContaNaoEncontradaException.class);
    }

    @Test
    void corrida_com_outro_envio_do_mesmo_dia_cai_na_regra_de_reenvio() {
        prepararConta(true);
        when(saldos.saldoEm(nubank, HOJE)).thenReturn(Dinheiro.de("800"));
        when(conciliacoes.inserir(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("conciliacoes_conta_id_data_key"));
        var vencedora = new Conciliacao(new ConciliacaoId(UUID.randomUUID()), nubank, HOJE, Dinheiro.de("770"),
                Dinheiro.de("800"), Dinheiro.de("-30"), null);
        when(conciliacoes.ultima(nubank)).thenReturn(Optional.of(base()), Optional.of(vencedora),
                Optional.of(vencedora));

        assertThat(servico.conciliar(nubank, HOJE, Dinheiro.de("770"), null).conciliacao()).isEqualTo(vencedora);
        assertThatThrownBy(() -> servico.conciliar(nubank, HOJE, Dinheiro.de("700"), null))
                .isInstanceOf(ConciliacaoForaDeOrdemException.class);
    }

    private Conciliacao prepararConta(boolean ativa) {
        when(contas.buscar(nubank)).thenReturn(Optional.of(new Conta(nubank, "Nubank", "Nu", TipoConta.CORRENTE,
                Funcao.GIRO, Dinheiro.ZERO, ativa)));
        var ultima = base();
        when(conciliacoes.ultima(nubank)).thenReturn(Optional.of(ultima));
        return ultima;
    }

    private Conciliacao base() {
        return new Conciliacao(new ConciliacaoId(UUID.randomUUID()), nubank, BASE, Dinheiro.de("1000"),
                Dinheiro.de("1000"), Dinheiro.ZERO, null);
    }
}
