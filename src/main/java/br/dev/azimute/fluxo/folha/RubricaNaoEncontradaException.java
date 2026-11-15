package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

final class RubricaNaoEncontradaException extends ErroDeNegocio {

    RubricaNaoEncontradaException(String codigo) {
        super("RUBRICA_NAO_ENCONTRADA", "Rubrica não encontrada no catálogo", Tipo.NAO_ENCONTRADO,
                Map.of("codigo", codigo));
    }
}
