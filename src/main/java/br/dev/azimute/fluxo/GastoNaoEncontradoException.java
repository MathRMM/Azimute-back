package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Nenhum gasto com esse ID; na edição, também o gasto excluído. */
final class GastoNaoEncontradoException extends ErroDeNegocio {

    GastoNaoEncontradoException(GastoId id) {
        super("GASTO_NAO_ENCONTRADO", "Gasto não encontrado", Tipo.NAO_ENCONTRADO, Map.of("id", id.toString()));
    }
}
