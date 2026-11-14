package br.dev.azimute.calculadora.historico;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static br.dev.azimute.calculadora.historico.MontadorDeSimulacao.estimativa;
import static br.dev.azimute.calculadora.historico.MontadorDeSimulacao.proposta;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF28, D6: o servidor recalcula o snapshot com o core e aponta cada campo divergente. */
class ConferenciaDeSnapshotTest {

    private final ConferenciaDeSnapshot conferencia = new ConferenciaDeSnapshot();
    private final SimulacaoId ancoraId = new SimulacaoId(UUID.randomUUID());
    private final SimulacaoSalva ancoraA1PorCento = proposta().salva(ancoraId);

    @Test
    void proposta_consistente_passa() {
        assertThatCode(() -> conferencia.conferir(proposta().montar(), null)).doesNotThrowAnyException();
    }

    @Test
    void totais_divergentes_apontam_enviado_e_esperado() {
        var dados = proposta().com(m -> {
            m.valorFinanciado = "999";
            m.totalPago = "1000";
            m.totalJuros = "11";
        }).montar();

        assertThat(divergencias(dados, null))
                .containsEntry("valorFinanciado", Map.of("enviado", "999.00", "esperado", "1000.00"))
                .containsEntry("totalPago", Map.of("enviado", "1000.00", "esperado", "1010.00"))
                .containsEntry("totalJuros", Map.of("enviado", "11.00", "esperado", "10.00"));
    }

    @Test
    void taxa_mensal_da_proposta_e_recalculada_a_partir_da_parcela() {
        var dados = proposta().com(m -> m.taxaMensal = "0.0101").montar();

        assertThat(divergencias(dados, null))
                .containsEntry("taxaMensal", Map.of("enviado", "0.01010000", "esperado", "0.01000000"));
    }

    @Test
    void taxa_anual_e_conferida_contra_a_mensal_enviada() {
        var dados = proposta().com(m -> m.taxaAnual = "0.127").montar();

        assertThat(divergencias(dados, null))
                .containsOnlyKeys("taxaAnual")
                .containsEntry("taxaAnual", Map.of("enviado", "0.12700000", "esperado", "0.12682503"));
    }

    @Test
    void estimativa_consistente_com_a_taxa_da_ancora_passa() {
        assertThatCode(() -> conferencia.conferir(estimativa(ancoraId).montar(), ancoraA1PorCento))
                .doesNotThrowAnyException();
    }

    @Test
    void estimativa_precisa_usar_exatamente_a_taxa_da_ancora() {
        var ancoraA2PorCento = proposta().com(m -> m.taxaMensal = "0.02").salva(ancoraId);

        assertThat(divergencias(estimativa(ancoraId).montar(), ancoraA2PorCento))
                .containsEntry("taxaMensal", Map.of("enviado", "0.01000000", "esperado", "0.02000000"))
                .containsEntry("parcela", Map.of("enviado", "1010.00", "esperado", "1020.00"));
    }

    @Test
    void parcela_da_estimativa_tolera_um_centavo() {
        var umCentavo = estimativa(ancoraId).com(m -> {
            m.parcela = "1010.01";
            m.totalPago = "1010.01";
            m.totalJuros = "10.01";
        }).montar();

        assertThatCode(() -> conferencia.conferir(umCentavo, ancoraA1PorCento)).doesNotThrowAnyException();
    }

    @Test
    void parcela_da_estimativa_com_dois_centavos_de_diferenca_diverge() {
        var doisCentavos = estimativa(ancoraId).com(m -> {
            m.parcela = "1010.02";
            m.totalPago = "1010.02";
            m.totalJuros = "10.02";
        }).montar();

        assertThat(divergencias(doisCentavos, ancoraA1PorCento))
                .containsOnlyKeys("parcela")
                .containsEntry("parcela", Map.of("enviado", "1010.02", "esperado", "1010.00"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> divergencias(DadosSimulacao dados, SimulacaoSalva ancora) {
        try {
            conferencia.conferir(dados, ancora);
        } catch (SnapshotDivergenteException e) {
            return (Map<String, Object>) e.detalhes().get("campos");
        }
        throw new AssertionError("esperava SnapshotDivergenteException");
    }
}
