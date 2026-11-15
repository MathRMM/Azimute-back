package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.CategoriaId;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O que um holerite deve gerar, como função pura (RF33, RN12, RN14, RN19): o ganho {@code SALARIO}, os recebimentos
 * na conta do salário, o FGTS, a previdência em três entradas e os gastos em folha. Cada lançamento tem uma
 * referência determinística (T6): reaplicar os efeitos encontra o mesmo lançamento e não duplica.
 * <p>
 * A natureza de cada rubrica vem do catálogo, não do texto (RN12). Rubrica fora do catálogo não gera efeito.
 */
final class EfeitosDoHolerite {

    record GanhoEsperado(UUID id, Dinheiro bruto, Dinheiro liquido) {}

    record RecebimentoEsperado(UUID id, String papel, Dinheiro valor, LocalDate data, ContaId conta,
                               boolean lancadoAntes) {}

    record LancamentoEsperado(UUID id, String papel, Dinheiro valor, LocalDate data, ContaId conta) {}

    record GastoEsperado(UUID id, String codigo, String descricao, Dinheiro valor, LocalDate data,
                         CategoriaId categoria) {}

    record Efeitos(GanhoEsperado ganho, List<RecebimentoEsperado> recebimentos, List<LancamentoEsperado> lancamentos,
                   List<GastoEsperado> gastos) {}

    private EfeitosDoHolerite() {
    }

    /**
     * @param adiantamentoAntecipado o recebimento lançado antes do holerite (RF34), que é associado com o próprio ID;
     *                               nulo quando não há
     */
    static Efeitos calcular(Holerite holerite, List<RubricaLida> rubricas, Map<String, RubricaCatalogo> catalogo,
                            ConfiguracaoFolha configuracao, UUID adiantamentoAntecipado) {
        var id = holerite.id();
        var recebimentos = new ArrayList<RecebimentoEsperado>();
        var adiantamento = soma(NaturezaRubrica.ADIANTAMENTO, rubricas, catalogo);
        if (adiantamento.positivo()) {
            var antecipado = adiantamentoAntecipado != null;
            recebimentos.add(new RecebimentoEsperado(antecipado ? adiantamentoAntecipado
                    : referencia(id, "ADIANTAMENTO"), "ADIANTAMENTO", adiantamento, holerite.dataAdiantamento(),
                    configuracao.salario(), antecipado));
        }
        if (holerite.liquido().positivo()) {
            recebimentos.add(new RecebimentoEsperado(referencia(id, "LIQUIDO"), "LIQUIDO", holerite.liquido(),
                    holerite.dataLiquido(), configuracao.salario(), false));
        }
        var recebido = recebimentos.stream().map(RecebimentoEsperado::valor).reduce(Dinheiro.ZERO, Dinheiro::mais);

        // P1: FGTS e previdência entram na data do líquido.
        var lancamentos = new ArrayList<LancamentoEsperado>();
        var data = holerite.dataLiquido();
        adicionar(lancamentos, id, "FGTS", holerite.bases().fgtsMes(), data, configuracao.fgts());
        adicionar(lancamentos, id, "PREV_BASICA", soma(NaturezaRubrica.PREVIDENCIA_BASICA, rubricas, catalogo), data,
                configuracao.previdencia());
        adicionar(lancamentos, id, "PREV_CONTRAPARTIDA", holerite.contrapartidaPrevidencia(), data,
                configuracao.previdencia());
        adicionar(lancamentos, id, "PREV_VOLUNTARIA",
                soma(NaturezaRubrica.PREVIDENCIA_VOLUNTARIA, rubricas, catalogo), data, configuracao.previdencia());

        return new Efeitos(new GanhoEsperado(referencia(id, "GANHO"), holerite.totalVencimentos(), recebido),
                List.copyOf(recebimentos), List.copyOf(lancamentos), gastos(holerite, rubricas, catalogo));
    }

    /** Soma das rubricas que o catálogo classifica com a natureza dada (impostos, previdência, adiantamento). */
    static Dinheiro soma(NaturezaRubrica natureza, List<RubricaLida> rubricas, Map<String, RubricaCatalogo> catalogo) {
        return rubricas.stream()
                .filter(r -> naturezaDe(r, catalogo) == natureza)
                .map(EfeitosDoHolerite::valor)
                .reduce(Dinheiro.ZERO, Dinheiro::mais);
    }

    /** T6: o mesmo holerite e o mesmo papel dão sempre a mesma referência. */
    static UUID referencia(HoleriteId holerite, String papel) {
        return UUID.nameUUIDFromBytes((holerite + ":" + papel).getBytes(StandardCharsets.UTF_8));
    }

    /** Um gasto por código de rubrica de gasto; a mesma rubrica repetida soma. */
    private static List<GastoEsperado> gastos(Holerite holerite, List<RubricaLida> rubricas,
                                              Map<String, RubricaCatalogo> catalogo) {
        var porCodigo = new LinkedHashMap<String, GastoEsperado>();
        for (var rubrica : rubricas) {
            if (naturezaDe(rubrica, catalogo) != NaturezaRubrica.GASTO) {
                continue;
            }
            var codigo = rubrica.codigo();
            var anterior = porCodigo.get(codigo);
            var valor = anterior == null ? valor(rubrica) : anterior.valor().mais(valor(rubrica));
            porCodigo.put(codigo, new GastoEsperado(referencia(holerite.id(), "GASTO:" + codigo), codigo,
                    rubrica.descricao(), valor, holerite.dataLiquido(), catalogo.get(codigo).categoria()));
        }
        return List.copyOf(porCodigo.values());
    }

    private static void adicionar(List<LancamentoEsperado> lancamentos, HoleriteId holerite, String papel,
                                  Dinheiro valor, LocalDate data, ContaId conta) {
        if (valor != null && valor.positivo()) {
            lancamentos.add(new LancamentoEsperado(referencia(holerite, papel), papel, valor, data, conta));
        }
    }

    private static NaturezaRubrica naturezaDe(RubricaLida rubrica, Map<String, RubricaCatalogo> catalogo) {
        var classificada = catalogo.get(rubrica.codigo());
        return classificada == null ? null : classificada.natureza();
    }

    private static Dinheiro valor(RubricaLida rubrica) {
        return rubrica.desconto() != null ? rubrica.desconto() : rubrica.vencimento();
    }
}
