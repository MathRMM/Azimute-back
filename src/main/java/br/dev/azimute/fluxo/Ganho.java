package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.fluxo.folha.HoleriteId;

import java.util.Objects;

/**
 * Dinheiro que entrou, por competência. O que cai na conta são os recebimentos; o ganho guarda bruto e líquido.
 * O líquido nunca supera o bruto (RN02); o salário vem sempre de um holerite (RF21).
 */
record Ganho(GanhoId id, Competencia competencia, String descricao, TipoGanho tipo, Dinheiro bruto,
             Dinheiro liquido, HoleriteId holerite) {

    Ganho {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(competencia, "competencia");
        Modelo.exigirTexto(descricao, "descricao");
        Objects.requireNonNull(tipo, "tipo");
        Modelo.exigirNaoNegativo(bruto, "bruto");
        Modelo.exigirNaoNegativo(liquido, "liquido");
        if (liquido.compareTo(bruto) > 0) {
            throw new LiquidoMaiorQueBrutoException(bruto, liquido);
        }
        if ((tipo == TipoGanho.SALARIO) != (holerite != null)) {
            throw new IllegalArgumentException("Só o salário vem do holerite, e sempre vem: " + tipo);
        }
    }
}
