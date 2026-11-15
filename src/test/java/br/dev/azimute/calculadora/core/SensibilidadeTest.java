package br.dev.azimute.calculadora.core;

import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF16, decisão T7. Exemplo do prompt do simulador: moto de R$ 19.990, R$ 6.000 de entrada, 24x a 4,14%. */
class SensibilidadeTest {

    private static final Dinheiro BEM = Dinheiro.de("19990.00");
    private static final Dinheiro ANCORA = Dinheiro.de("6000.00");
    private static final TaxaMensal TAXA = new TaxaMensal(new BigDecimal("0.0413642"));

    private List<PontoSensibilidade> curva(int passos) {
        return Sensibilidade.curvaPorEntrada(BEM, ANCORA, TAXA, 24, passos);
    }

    @Test
    void ponto_da_ancora_tem_distancia_zero_e_a_parcela_da_proposta() {
        var ancora = curva(11).stream().filter(p -> p.entrada().equals(ANCORA)).toList();

        assertThat(ancora).hasSize(1);
        assertThat(ancora.getFirst().distanciaDaAncora()).isEqualByComparingTo("0");
        assertThat(ancora.getFirst().parcela()).isEqualTo(JurosCompostos.parcela(Dinheiro.de("13990.00"), TAXA, 24));
        assertThat(ancora.getFirst().parcela()).isEqualTo(Dinheiro.de("930.42"));
    }

    @Test
    void vai_de_zero_ao_valor_do_bem() {
        var pontos = curva(11);

        assertThat(pontos.getFirst().entrada()).isEqualTo(Dinheiro.ZERO);
        assertThat(pontos.getFirst().financiado()).isEqualTo(BEM);

        var aVista = pontos.getLast();
        assertThat(aVista.entrada()).isEqualTo(BEM);
        assertThat(aVista.financiado()).isEqualTo(Dinheiro.ZERO);
        assertThat(aVista.parcela()).isEqualTo(Dinheiro.ZERO);
        assertThat(aVista.totalPago()).isEqualTo(Dinheiro.ZERO);
        assertThat(aVista.totalJuros()).isEqualTo(Dinheiro.ZERO);
    }

    @Test
    void grade_mais_ancora_quando_ela_nao_coincide() {
        // 11 passos: entradas de 1.999 em 1.999; R$ 6.000 não está na grade
        assertThat(curva(11)).hasSize(12);
    }

    @Test
    void ancora_que_coincide_com_a_grade_nao_e_duplicada() {
        var pontos = Sensibilidade.curvaPorEntrada(Dinheiro.de("20000.00"), Dinheiro.de("5000.00"), TAXA, 24, 5);
        assertThat(pontos).extracting(PontoSensibilidade::entrada)
                .containsExactly(Dinheiro.de("0"), Dinheiro.de("5000"), Dinheiro.de("10000"),
                        Dinheiro.de("15000"), Dinheiro.de("20000"));
    }

    @Test
    void entradas_em_ordem_crescente_e_parcela_nao_cresce() {
        var pontos = curva(41);
        for (int k = 1; k < pontos.size(); k++) {
            assertThat(pontos.get(k).entrada()).isGreaterThan(pontos.get(k - 1).entrada());
            assertThat(pontos.get(k).parcela()).isLessThanOrEqualTo(pontos.get(k - 1).parcela());
        }
    }

    @Test
    void distancia_cresce_ao_se_afastar_da_ancora_nos_dois_sentidos() {
        var pontos = curva(41);
        int iAncora = pontos.indexOf(pontos.stream().filter(p -> p.entrada().equals(ANCORA)).findFirst().orElseThrow());

        for (int k = iAncora + 1; k < pontos.size(); k++) {
            assertThat(pontos.get(k).distanciaDaAncora()).isGreaterThan(pontos.get(k - 1).distanciaDaAncora());
        }
        for (int k = iAncora - 1; k >= 0; k--) {
            assertThat(pontos.get(k).distanciaDaAncora()).isGreaterThan(pontos.get(k + 1).distanciaDaAncora());
        }
    }

    @Test
    void distancia_e_fracao_do_valor_do_bem_com_quatro_casas() {
        var pontos = Sensibilidade.curvaPorEntrada(BEM, ANCORA, TAXA, 24, 2);
        // pontos: 0, 6.000 (âncora), 19.990
        assertThat(pontos.getFirst().distanciaDaAncora()).isEqualByComparingTo("0.3002"); // 6000/19990 = 0,300150…
        assertThat(pontos.getLast().distanciaDaAncora()).isEqualByComparingTo("0.6998");  // 13990/19990 = 0,699849…
        assertThat(pontos.getFirst().distanciaDaAncora().scale()).isEqualTo(4);
    }

    @Test
    void exemplo_do_prompt_9000_de_entrada() {
        // grade de 19.990 / 19.990 = R$ 1 por passo; inclui R$ 9.000
        var nove = Sensibilidade.curvaPorEntrada(BEM, ANCORA, TAXA, 24, 19991).stream()
                .filter(p -> p.entrada().equals(Dinheiro.de("9000.00"))).findFirst().orElseThrow();
        assertThat(nove.distanciaDaAncora()).isEqualByComparingTo("0.1501"); // 3000/19990
        assertThat(nove.financiado()).isEqualTo(Dinheiro.de("10990.00"));
    }

    @Test
    void totais_de_cada_ponto_sao_coerentes() {
        for (var p : curva(21)) {
            assertThat(p.financiado()).isEqualTo(BEM.menos(p.entrada()));
            assertThat(p.totalPago()).isEqualTo(p.parcela().vezes(24));
            assertThat(p.totalJuros()).isEqualTo(p.totalPago().menos(p.financiado()));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "19990, 6000, 1",      // passos < 2
            "19990, 19990, 11",    // âncora = bem: não houve financiamento
            "19990, 25000, 11",    // âncora acima do bem
            "19990, -1, 11",       // âncora negativa
            "0, 0, 11"             // bem sem valor
    })
    void parametros_invalidos_sao_rejeitados(String bem, String ancora, int passos) {
        assertThatThrownBy(() -> Sensibilidade.curvaPorEntrada(Dinheiro.de(bem), Dinheiro.de(ancora), TAXA, 24, passos))
                .isInstanceOf(ValorInvalidoException.class);
    }

    @Test
    void prazo_invalido_e_rejeitado() {
        assertThatThrownBy(() -> Sensibilidade.curvaPorEntrada(BEM, ANCORA, TAXA, 0, 11))
                .isInstanceOf(ValorInvalidoException.class);
    }
}
