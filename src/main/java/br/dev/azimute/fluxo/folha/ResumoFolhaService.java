package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContasFacade;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Resumo anual da folha (RF39, T10): impostos e previdência saem das rubricas e do catálogo atual, na leitura; a
 * contrapartida é a gravada em cada holerite (RN19). O acumulado do FGTS e da previdência é o saldo das contas.
 */
@Service
class ResumoFolhaService {

    /** Um mês, ou o ano quando a competência é nula. Impostos = INSS + IRRF; previdência = as três partes. */
    record Linha(Competencia competencia, Dinheiro bruto, Dinheiro inss, Dinheiro irrf, Dinheiro impostos,
                 Dinheiro previdenciaBasica, Dinheiro previdenciaContrapartida, Dinheiro previdenciaVoluntaria,
                 Dinheiro previdenciaTotal, Dinheiro fgts, Dinheiro liquidoRecebido) {

        static final Linha ZERO = new Linha(null, Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO,
                Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO);

        Linha mais(Linha outra) {
            return new Linha(null, bruto.mais(outra.bruto), inss.mais(outra.inss), irrf.mais(outra.irrf),
                    impostos.mais(outra.impostos), previdenciaBasica.mais(outra.previdenciaBasica),
                    previdenciaContrapartida.mais(outra.previdenciaContrapartida),
                    previdenciaVoluntaria.mais(outra.previdenciaVoluntaria),
                    previdenciaTotal.mais(outra.previdenciaTotal), fgts.mais(outra.fgts),
                    liquidoRecebido.mais(outra.liquidoRecebido));
        }
    }

    /** O acumulado é nulo sem a folha configurada. */
    record Resumo(int ano, List<Linha> meses, Linha total, Dinheiro fgtsAcumulado, Dinheiro previdenciaAcumulada) {}

    private final HoleriteRepository holerites;
    private final CatalogoRepository catalogo;
    private final ConfiguracaoFolhaRepository configuracoes;
    private final ContasFacade contas;

    ResumoFolhaService(HoleriteRepository holerites, CatalogoRepository catalogo,
                       ConfiguracaoFolhaRepository configuracoes, ContasFacade contas) {
        this.holerites = holerites;
        this.catalogo = catalogo;
        this.configuracoes = configuracoes;
        this.contas = contas;
    }

    Resumo resumo(int ano) {
        var mapa = catalogo.mapa();
        var meses = new ArrayList<Linha>();
        var total = Linha.ZERO;
        for (var h : holerites.listar(ano)) {
            var rubricas = holerites.rubricas(h.id());
            var inss = EfeitosDoHolerite.soma(NaturezaRubrica.INSS, rubricas, mapa);
            var irrf = EfeitosDoHolerite.soma(NaturezaRubrica.IRRF, rubricas, mapa);
            var basica = EfeitosDoHolerite.soma(NaturezaRubrica.PREVIDENCIA_BASICA, rubricas, mapa);
            var voluntaria = EfeitosDoHolerite.soma(NaturezaRubrica.PREVIDENCIA_VOLUNTARIA, rubricas, mapa);
            var contrapartida = h.contrapartidaPrevidencia();
            var fgts = h.bases().fgtsMes() == null ? Dinheiro.ZERO : h.bases().fgtsMes();
            var recebido = EfeitosDoHolerite.soma(NaturezaRubrica.ADIANTAMENTO, rubricas, mapa).mais(h.liquido());
            var linha = new Linha(h.competencia(), h.totalVencimentos(), inss, irrf, inss.mais(irrf), basica,
                    contrapartida, voluntaria, basica.mais(contrapartida).mais(voluntaria), fgts, recebido);
            meses.add(linha);
            total = total.mais(linha);
        }
        var configuracao = configuracoes.buscar();
        return new Resumo(ano, meses, total,
                configuracao.map(c -> contas.saldoAtual(c.fgts())).orElse(null),
                configuracao.map(c -> contas.saldoAtual(c.previdencia())).orElse(null));
    }
}
