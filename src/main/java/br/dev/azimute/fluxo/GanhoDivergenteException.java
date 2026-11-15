package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Mesmo ID com outro conteúdo no {@code PUT} (RF20). */
final class GanhoDivergenteException extends ErroDeNegocio {

    GanhoDivergenteException(GanhoId id) {
        super("GANHO_DIVERGENTE", "Já existe um ganho com esse ID e outro conteúdo", Tipo.CONFLITO,
                Map.of("id", id.toString()));
    }
}
