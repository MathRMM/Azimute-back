package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

final class HoleriteNaoEncontradoException extends ErroDeNegocio {

    HoleriteNaoEncontradoException(HoleriteId id) {
        super("HOLERITE_NAO_ENCONTRADO", "Holerite não encontrado", Tipo.NAO_ENCONTRADO, Map.of("id", id.toString()));
    }
}
