package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.fluxo.GanhoId;
import br.dev.azimute.fluxo.GastoId;
import br.dev.azimute.fluxo.tabelas.PapelDeRecebimento;
import br.dev.azimute.fluxo.tabelas.RecebimentoId;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static br.dev.azimute.fluxo.folha.HoleriteDeExemplo.CATALOGO;
import static br.dev.azimute.fluxo.folha.HoleriteDeExemplo.CONFIGURACAO;
import static org.assertj.core.api.Assertions.assertThat;

/** RF33, RN12, RN14, RN19, T6: o que um holerite deve gerar, como função pura. */
class EfeitosDoHoleriteTest {

    private static final LocalDate DIA_15 = LocalDate.of(2026, 10, 15);
    private static final LocalDate DIA_30 = LocalDate.of(2026, 10, 30);

    private final HoleriteId id = new HoleriteId(UUID.randomUUID());

    /** T6: a referência v3 é o que impede a reaplicação de duplicar; ela não pode mudar nunca. */
    @Test
    void referencias_deterministicas_nao_mudam() {
        var holerite = new HoleriteId(UUID.fromString("0190f3a0-0000-7000-8000-000000000001"));

        assertThat(EfeitosDoHolerite.referencia(holerite, "GANHO"))
                .isEqualTo(UUID.fromString("63cc8c81-aab4-3cd9-9ca0-343d40321877"));
        assertThat(EfeitosDoHolerite.referencia(holerite, "LIQUIDO"))
                .isEqualTo(UUID.fromString("2ef0802b-6f7c-39da-9c9b-299ee74fca92"));
        assertThat(EfeitosDoHolerite.referencia(holerite, "GASTO:/507"))
                .isEqualTo(UUID.fromString("7a19ba74-7843-36d3-87d2-5c45bd6b877b"));
        assertThat(EfeitosDoHolerite.referencia(holerite, "GANHO").version()).isEqualTo(3);
    }

    @Test
    void ganho_salario_com_bruto_dos_vencimentos_e_liquido_dos_dois_recebimentos() {
        var efeitos = calcular("50", HoleriteDeExemplo.rubricas(), null);

        assertThat(efeitos.ganho()).isEqualTo(new EfeitosDoHolerite.GanhoEsperado(
                new GanhoId(EfeitosDoHolerite.referencia(id, "GANHO")), Dinheiro.de("5000"), Dinheiro.de("3920")));
        assertThat(efeitos.recebimentos()).containsExactly(
                new EfeitosDoHolerite.RecebimentoEsperado(new RecebimentoId(EfeitosDoHolerite.referencia(id, "ADIANTAMENTO")),
                        PapelDeRecebimento.ADIANTAMENTO, Dinheiro.de("2500"), DIA_15, CONFIGURACAO.salario(), false),
                new EfeitosDoHolerite.RecebimentoEsperado(new RecebimentoId(EfeitosDoHolerite.referencia(id, "LIQUIDO")), PapelDeRecebimento.LIQUIDO,
                        Dinheiro.de("1420"), DIA_30, CONFIGURACAO.salario(), false));
    }

    @Test
    void fgts_e_a_previdencia_em_tres_lancamentos_na_data_do_liquido() {
        var efeitos = calcular("50", HoleriteDeExemplo.rubricas(), null);

        assertThat(efeitos.lancamentos()).containsExactly(
                lancamento(PapelDeLancamento.FGTS, "400", CONFIGURACAO.fgts()),
                lancamento(PapelDeLancamento.PREV_BASICA, "50", CONFIGURACAO.previdencia()),
                lancamento(PapelDeLancamento.PREV_CONTRAPARTIDA, "50", CONFIGURACAO.previdencia()),
                lancamento(PapelDeLancamento.PREV_VOLUNTARIA, "250", CONFIGURACAO.previdencia()));
    }

    @Test
    void vale_transporte_vira_gasto_em_folha_na_categoria_do_catalogo() {
        var efeitos = calcular("50", HoleriteDeExemplo.rubricas(), null);

        assertThat(efeitos.gastos()).containsExactly(new EfeitosDoHolerite.GastoEsperado(
                new GastoId(EfeitosDoHolerite.referencia(id, "GASTO:/507")), "/507", "Vale-Transporte", Dinheiro.de("300"),
                DIA_30, HoleriteDeExemplo.TRANSPORTE));
    }

    @Test
    void mesmas_entradas_produzem_as_mesmas_referencias() {
        var primeira = calcular("50", HoleriteDeExemplo.rubricas(), null);
        var segunda = calcular("50", HoleriteDeExemplo.rubricas(), null);
        var deOutroHolerite = EfeitosDoHolerite.calcular(HoleriteDeExemplo.holerite(new HoleriteId(UUID.randomUUID()),
                "50"), HoleriteDeExemplo.rubricas(), CATALOGO, CONFIGURACAO, null);

        assertThat(segunda).isEqualTo(primeira);
        assertThat(deOutroHolerite.ganho().id()).isNotEqualTo(primeira.ganho().id());
    }

    @Test
    void sem_adiantamento_ha_um_recebimento_so_e_sem_fgts_nem_contrapartida_nao_ha_lancamento() {
        var rubricas = new ArrayList<>(HoleriteDeExemplo.rubricas());
        rubricas.removeIf(r -> r.codigo().equals("/B02"));
        var semFgts = new Holerite(id, HoleriteDeExemplo.OUTUBRO, Holerite.Origem.MANUAL, "h", Dinheiro.de("5000"),
                Dinheiro.de("1080"), Dinheiro.de("3920"), new BasesLidas(null, null, null, null, null), Dinheiro.ZERO,
                DIA_15, DIA_30);

        var efeitos = EfeitosDoHolerite.calcular(semFgts, rubricas, CATALOGO, CONFIGURACAO, null);

        assertThat(efeitos.recebimentos()).extracting(EfeitosDoHolerite.RecebimentoEsperado::papel)
                .containsExactly(PapelDeRecebimento.LIQUIDO);
        assertThat(efeitos.ganho().liquido()).isEqualTo(Dinheiro.de("3920"));
        assertThat(efeitos.lancamentos()).extracting(EfeitosDoHolerite.LancamentoEsperado::papel)
                .containsExactly(PapelDeLancamento.PREV_BASICA, PapelDeLancamento.PREV_VOLUNTARIA);
    }

    @Test
    void adiantamento_lancado_antes_e_associado_com_o_proprio_id() {
        var antecipado = new RecebimentoId(UUID.randomUUID());

        var efeitos = calcular("50", HoleriteDeExemplo.rubricas(), antecipado);

        assertThat(efeitos.recebimentos().getFirst()).isEqualTo(new EfeitosDoHolerite.RecebimentoEsperado(
                antecipado, PapelDeRecebimento.ADIANTAMENTO, Dinheiro.de("2500"), DIA_15, CONFIGURACAO.salario(), true));
    }

    @Test
    void rubrica_fora_do_catalogo_ou_sem_efeito_nao_gera_lancamento_e_rubricas_repetidas_somam() {
        var rubricas = new ArrayList<>(HoleriteDeExemplo.rubricas());
        rubricas.add(HoleriteDeExemplo.desconto("9999", "Desconhecida", "10"));
        rubricas.add(HoleriteDeExemplo.desconto("/507", "Vale-Transporte", "20"));
        var catalogo = new HashMap<>(CATALOGO);
        catalogo.put("8888", new RubricaCatalogo("8888", "Outra", NaturezaRubrica.OUTRO_DESCONTO, null, true));
        rubricas.add(HoleriteDeExemplo.desconto("8888", "Outra", "5"));

        var efeitos = EfeitosDoHolerite.calcular(HoleriteDeExemplo.holerite(id, "50"), rubricas, catalogo,
                CONFIGURACAO, null);

        assertThat(efeitos.gastos()).singleElement()
                .satisfies(g -> assertThat(g.valor()).isEqualTo(Dinheiro.de("320")));
        assertThat(efeitos.lancamentos()).hasSize(4);
    }

    @Test
    void soma_por_natureza_serve_aos_impostos_e_a_previdencia() {
        var rubricas = HoleriteDeExemplo.rubricas();

        assertThat(EfeitosDoHolerite.soma(NaturezaRubrica.INSS, rubricas, CATALOGO)).isEqualTo(Dinheiro.de("480"));
        assertThat(EfeitosDoHolerite.soma(NaturezaRubrica.IRRF, rubricas, CATALOGO)).isEqualTo(Dinheiro.ZERO);
        assertThat(EfeitosDoHolerite.soma(NaturezaRubrica.PREVIDENCIA_BASICA, rubricas, CATALOGO))
                .isEqualTo(Dinheiro.de("50"));
        assertThat(EfeitosDoHolerite.soma(NaturezaRubrica.PROVENTO, List.of(), CATALOGO)).isEqualTo(Dinheiro.ZERO);
    }

    private EfeitosDoHolerite.Efeitos calcular(String contrapartida, List<RubricaLida> rubricas, RecebimentoId antecipado) {
        return EfeitosDoHolerite.calcular(HoleriteDeExemplo.holerite(id, contrapartida), rubricas, CATALOGO,
                CONFIGURACAO, antecipado);
    }

    private EfeitosDoHolerite.LancamentoEsperado lancamento(PapelDeLancamento papel, String valor,
                                                            br.dev.azimute.contas.ContaId conta) {
        return new EfeitosDoHolerite.LancamentoEsperado(new LancamentoDaFolhaId(EfeitosDoHolerite.referencia(id, papel.name())), papel,
                Dinheiro.de(valor), DIA_30, conta);
    }
}
