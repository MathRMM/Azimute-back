package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RN10: os totais não batem com as rubricas. Aponta a conferência que falhou; nada é gravado. */
final class HoleriteInconsistenteException extends ErroDeNegocio {

    HoleriteInconsistenteException(String conferencia, Dinheiro informado, Dinheiro calculado) {
        super("HOLERITE_INCONSISTENTE", "Os totais do holerite não batem com as rubricas", Tipo.VALIDACAO,
                Map.of("conferencia", conferencia, "informado", informado.toString(),
                        "calculado", calculado.toString()));
    }
}
