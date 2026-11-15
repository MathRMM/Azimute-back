package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Nenhum ganho com esse ID; na edição, também o ganho excluído. */
final class GanhoNaoEncontradoException extends ErroDeNegocio {

    GanhoNaoEncontradoException(GanhoId id) {
        super("GANHO_NAO_ENCONTRADO", "Ganho não encontrado", Tipo.NAO_ENCONTRADO, Map.of("id", id.toString()));
    }
}
