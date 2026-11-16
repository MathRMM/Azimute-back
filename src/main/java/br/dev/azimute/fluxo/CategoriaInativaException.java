package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RN05: categoria inativa não recebe gasto novo, nem por edição. */
final class CategoriaInativaException extends ErroDeNegocio {

    CategoriaInativaException(CategoriaId id) {
        super("CATEGORIA_INATIVA", "A categoria está desativada e não recebe gastos", Tipo.VALIDACAO,
                Map.of("categoria", id.toString()));
    }
}
