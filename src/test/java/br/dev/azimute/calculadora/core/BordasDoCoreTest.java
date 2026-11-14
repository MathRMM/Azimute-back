package br.dev.azimute.calculadora.core;

import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RN04, D5: entradas degeneradas e os limites da faixa de taxa que os vetores de ouro não exercitam. */
class BordasDoCoreTest {

    private static final TaxaMensal UM_POR_CENTO = new TaxaMensal(new BigDecimal("0.01"));

    @Test
    void prazo_negativo_e_recusado_no_montante_e_no_valor_presente() {
        assertThatThrownBy(() -> JurosCompostos.montante(Dinheiro.de("100"), UM_POR_CENTO, -1))
                .isInstanceOf(ValorInvalidoException.class);
        assertThatThrownBy(() -> JurosCompostos.valorPresente(Dinheiro.de("100"), UM_POR_CENTO, -1))
                .isInstanceOf(ValorInvalidoException.class);
    }

    @Test
    void valor_financiado_negativo_e_recusado() {
        assertThatThrownBy(() -> JurosCompostos.parcela(Dinheiro.de("-1"), UM_POR_CENTO, 12))
                .isInstanceOf(ValorInvalidoException.class)
                .hasMessageContaining("não pode ser negativo");
    }

    @Test
    void taxa_abaixo_do_minimo_no_comparador_vira_zero() {
        // R$ 1.000.000 em 1 parcela de R$ 1.000.000,01: 1e-8 a.m., abaixo de 0,0001%
        var veredito = Comparador.compararAVistaVsFinanciado(Dinheiro.de("1000000"), Dinheiro.ZERO,
                Dinheiro.de("1000000.01"), 1, UM_POR_CENTO);

        assertThat(veredito.taxaFinanciamento()).isEqualTo(TaxaMensal.ZERO);
    }

    @Test
    void taxa_acima_do_maximo_no_comparador_propaga_o_erro() {
        // R$ 100 em 1 parcela de R$ 200: 100% a.m.
        assertThatThrownBy(() -> Comparador.compararAVistaVsFinanciado(Dinheiro.de("100"), Dinheiro.ZERO,
                Dinheiro.de("200"), 1, UM_POR_CENTO))
                .isInstanceOfSatisfying(TaxaForaDaFaixaException.class,
                        e -> assertThat(e.detalhes()).containsEntry("limite", "MAXIMO"));
    }

    @Test
    void veredito_diz_que_a_vista_vence_so_com_delta_positivo() {
        assertThat(veredito("0.01").aVistaVence()).isTrue();
        assertThat(veredito("0").aVistaVence()).isFalse();
        assertThat(veredito("-0.01").aVistaVence()).isFalse();
    }

    @Test
    void ponto_de_sensibilidade_so_e_ancora_com_distancia_zero() {
        assertThat(ponto("0").ancora()).isTrue();
        assertThat(ponto("0.0001").ancora()).isFalse();
    }

    @Test
    void taxa_mensal_imprime_a_fracao() {
        assertThat(new TaxaMensal(new BigDecimal("0.0351")).toString()).isEqualTo("0.0351");
    }

    @Test
    void falta_de_convergencia_e_um_erro_de_validacao_legivel() {
        var erro = new NaoConvergiuException(200);

        assertThat(erro.codigo()).isEqualTo("NAO_CONVERGIU");
        assertThat(erro.detalhes()).containsEntry("iteracoes", 200);
    }

    private static Veredito veredito(String delta) {
        return new Veredito(Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.de(delta), TaxaMensal.ZERO,
                TaxaMensal.ZERO);
    }

    private static PontoSensibilidade ponto(String distancia) {
        return new PontoSensibilidade(Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO,
                new BigDecimal(distancia));
    }
}
