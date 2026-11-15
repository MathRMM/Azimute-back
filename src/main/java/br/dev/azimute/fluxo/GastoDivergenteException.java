package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Mesmo ID com outro conteúdo no {@code PUT} (RF10). */
final class GastoDivergenteException extends ErroDeNegocio {

    GastoDivergenteException(GastoId id) {
        super("GASTO_DIVERGENTE", "Já existe um gasto com esse ID e outro conteúdo", Tipo.CONFLITO,
                Map.of("id", id.toString()));
    }
}
