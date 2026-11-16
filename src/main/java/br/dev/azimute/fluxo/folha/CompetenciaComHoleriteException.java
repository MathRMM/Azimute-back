package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RF34: com o holerite da competência já importado, o adiantamento já veio dele. */
final class CompetenciaComHoleriteException extends ErroDeNegocio {

    CompetenciaComHoleriteException(Competencia competencia) {
        super("COMPETENCIA_COM_HOLERITE", "O holerite desta competência já foi importado e traz o adiantamento",
                Tipo.CONFLITO, Map.of("competencia", competencia.toString()));
    }
}
