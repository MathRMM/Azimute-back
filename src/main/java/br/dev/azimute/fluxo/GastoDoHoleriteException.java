package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RF15, RN15: gasto descontado em folha só muda pelo holerite; pela API, só a categoria. */
final class GastoDoHoleriteException extends ErroDeNegocio {

    GastoDoHoleriteException(GastoId id) {
        super("GASTO_DO_HOLERITE", "Gasto descontado em folha só muda pelo holerite; aqui, só a categoria",
                Tipo.VALIDACAO, Map.of("id", id.toString()));
    }
}
