package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RF22, RN15: o ganho que nasceu do holerite só muda pelo holerite. */
final class GanhoDoHoleriteException extends ErroDeNegocio {

    GanhoDoHoleriteException(GanhoId id) {
        super("GANHO_DO_HOLERITE", "Ganho do holerite só muda pelo holerite", Tipo.VALIDACAO,
                Map.of("id", id.toString()));
    }
}
