package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RNF03, RNF04, SEC06: o leitor do layout "Demonstrativo de Pagamento", sobre o holerite sintético (SEC03). */
class LeitorDemonstrativoPagamentoTest {

    private final LeitorDeHolerite leitor = new LeitorDemonstrativoPagamento();

    @Test
    void le_competencia_as_seis_rubricas_totais_liquido_e_bases_exatos() {
        var lido = leitor.ler(HoleriteSintetico.doExemplo().pdf());

        assertThat(lido.competencia()).isEqualTo(Competencia.de("2026-10"));
        assertThat(lido.rubricas()).containsExactly(
                new RubricaLida("0001", "Salário Mensal", new BigDecimal("30.00"), Dinheiro.de("5000"), null),
                new RubricaLida("/314", "INSS", new BigDecimal("14.00"), null, Dinheiro.de("480")),
                new RubricaLida("/507", "Vale-Transporte", new BigDecimal("6.00"), null, Dinheiro.de("300")),
                new RubricaLida("2363", "Prev Priv Bas Func", new BigDecimal("1.00"), null, Dinheiro.de("50")),
                new RubricaLida("2364", "Prev Priv Vol Func", new BigDecimal("5.00"), null, Dinheiro.de("250")),
                new RubricaLida("/B02", "Decto Adto Salário", null, null, Dinheiro.de("2500")));
        assertThat(lido.totalVencimentos()).isEqualTo(Dinheiro.de("5000"));
        assertThat(lido.totalDescontos()).isEqualTo(Dinheiro.de("3580"));
        assertThat(lido.liquido()).isEqualTo(Dinheiro.de("1420"));
        assertThat(lido.bases()).isEqualTo(new BasesLidas(Dinheiro.de("5000"), Dinheiro.de("5000"),
                Dinheiro.de("5000"), Dinheiro.de("400"), Dinheiro.de("4170")));
    }

    @Test
    void le_numero_com_milhares_e_a_competencia_de_outro_mes() {
        var pdf = HoleriteSintetico.doExemplo().naCompetencia("Março 2027")
                .comRubrica("0100", "Bônus", "", "1.234.567,89", "").pdf();

        var lido = leitor.ler(pdf);

        assertThat(lido.competencia()).isEqualTo(Competencia.de("2027-03"));
        assertThat(lido.rubricas().getLast().vencimento()).isEqualTo(Dinheiro.de("1234567.89"));
        assertThat(lido.rubricas().getLast().referencia()).isNull();
    }

    @Test
    void base_em_branco_vira_nula() {
        var lido = leitor.ler(HoleriteSintetico.doExemplo().semFgtsDoMes().pdf());

        assertThat(lido.bases().fgtsMes()).isNull();
        assertThat(lido.bases().baseIrrf()).isEqualTo(Dinheiro.de("4170"));
    }

    @Test
    void sem_o_cabecalho_do_layout_e_layout_desconhecido() {
        assertIlegivel(HoleriteSintetico.doExemplo().semCabecalho().pdf(), "layout");
    }

    @Test
    void sem_a_linha_do_liquido_aponta_liquido() {
        assertIlegivel(HoleriteSintetico.doExemplo().semLiquido().pdf(), "liquido");
    }

    @Test
    void competencia_que_nao_e_um_mes_por_extenso_aponta_competencia() {
        assertIlegivel(HoleriteSintetico.doExemplo().naCompetencia("10/2026").pdf(), "competencia");
    }

    @Test
    void rubrica_sem_valor_ou_com_numero_invalido_aponta_a_rubrica() {
        assertIlegivel(HoleriteSintetico.doExemplo().comRubrica("0100", "Bônus", "", "", "").pdf(), "rubricas");
        assertIlegivel(HoleriteSintetico.doExemplo().comRubrica("0100", "Bônus", "", "1,2,3", "").pdf(), "rubricas");
    }

    @Test
    void tres_paginas_ou_cifrado_sao_recusados() {
        assertIlegivel(HoleriteSintetico.doExemplo().comPaginasExtras(1).pdf(), "paginas");
        assertIlegivel(HoleriteSintetico.doExemplo().cifrado().pdf(), "cifrado");
    }

    @Test
    void bytes_que_nao_sao_pdf_valido_sao_recusados() {
        assertIlegivel("%PDF-1.7 quebrado".getBytes(StandardCharsets.US_ASCII), "pdf");
    }

    private void assertIlegivel(byte[] pdf, String campo) {
        assertThatThrownBy(() -> leitor.ler(pdf))
                .isInstanceOfSatisfying(HoleriteIlegivelException.class, e -> {
                    assertThat(e.codigo()).isEqualTo("HOLERITE_ILEGIVEL");
                    assertThat(e.detalhes()).containsEntry("campo", campo);
                });
    }
}
