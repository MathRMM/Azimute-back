package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

final class CategoriaNaoEncontradaException extends ErroDeNegocio {

    CategoriaNaoEncontradaException(CategoriaId id) {
        super("CATEGORIA_NAO_ENCONTRADA", "Categoria não encontrada", Tipo.NAO_ENCONTRADO,
                Map.of("id", id.toString()));
    }
}
