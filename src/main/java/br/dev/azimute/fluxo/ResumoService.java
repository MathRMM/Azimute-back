package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resumo do mês (RF50, RN17, T14). */
@Service
class ResumoService {

    private static final BigDecimal TRES = BigDecimal.valueOf(3);

    private final ResumoRepository resumos;

    ResumoService(ResumoRepository resumos) {
        this.resumos = resumos;
    }

    ResumoMes resumoDoMes(Competencia competencia) {
        return montar(competencia, resumos.gastos(competencia), resumos.totaisDosTresMesesAnteriores(competencia),
                resumos.recebido(competencia));
    }

    /**
     * Agrupa os gastos da competência por categoria e põe ao lado a média simples dos 3 meses anteriores, com zero
     * onde não houve gasto (RN17). Categoria só com histórico aparece com total zero. Ordem: maior total primeiro.
     */
    static ResumoMes montar(Competencia competencia, List<ResumoRepository.GastoDoMes> gastos,
                            List<ResumoRepository.GastoDoMes> anteriores, Dinheiro recebido) {
        var nomes = new LinkedHashMap<CategoriaId, String>();
        var totais = new LinkedHashMap<CategoriaId, Dinheiro>();
        var totalGasto = Dinheiro.ZERO;
        for (var gasto : gastos) {
            nomes.putIfAbsent(gasto.categoria(), gasto.nome());
            totais.merge(gasto.categoria(), gasto.valor(), Dinheiro::mais);
            totalGasto = totalGasto.mais(gasto.valor());
        }
        var historico = new LinkedHashMap<CategoriaId, Dinheiro>();
        for (var anterior : anteriores) {
            nomes.putIfAbsent(anterior.categoria(), anterior.nome());
            historico.merge(anterior.categoria(), anterior.valor(), Dinheiro::mais);
        }

        var categorias = nomes.entrySet().stream()
                .map(e -> new ResumoMes.PorCategoria(e.getKey(), e.getValue(), valor(totais, e.getKey()),
                        media(valor(historico, e.getKey()))))
                .sorted(Comparator.comparing(ResumoMes.PorCategoria::total).reversed()
                        .thenComparing(ResumoMes.PorCategoria::nome))
                .toList();
        return new ResumoMes(competencia, totalGasto, recebido, recebido.menos(totalGasto), categorias);
    }

    private static Dinheiro valor(Map<CategoriaId, Dinheiro> mapa, CategoriaId categoria) {
        return mapa.getOrDefault(categoria, Dinheiro.ZERO);
    }

    private static Dinheiro media(Dinheiro somaDosTresMeses) {
        return new Dinheiro(somaDosTresMeses.valor().divide(TRES, Dinheiro.ESCALA, Dinheiro.ARREDONDAMENTO));
    }
}
