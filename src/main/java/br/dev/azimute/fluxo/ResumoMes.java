package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;

import java.util.List;

/**
 * O mês do usuário (RF50): total gasto (à vista e em folha), cada categoria ao lado da média dos 3 meses anteriores
 * (RN17), o recebido (líquido dos ganhos da competência) e a sobra.
 */
public record ResumoMes(Competencia competencia, Dinheiro totalGasto, Dinheiro recebido, Dinheiro sobra,
                        List<PorCategoria> categorias) {

    public ResumoMes {
        categorias = List.copyOf(categorias);
    }

    public record PorCategoria(CategoriaId categoria, String nome, Dinheiro total, Dinheiro mediaTresMeses) {}
}
