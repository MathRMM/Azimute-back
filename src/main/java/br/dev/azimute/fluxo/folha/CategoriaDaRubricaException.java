package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeNegocio;
import br.dev.azimute.fluxo.CategoriaId;

import java.util.Map;

/**
 * A categoria de uma rubrica de gasto não existe ou está inativa (RN05). Mesmos códigos dos erros de categoria do
 * pacote base, que são internos a ele.
 */
final class CategoriaDaRubricaException extends ErroDeNegocio {

    private CategoriaDaRubricaException(String codigo, String mensagem, Tipo tipo, CategoriaId id) {
        super(codigo, mensagem, tipo, Map.of("categoria", id.toString()));
    }

    static CategoriaDaRubricaException naoEncontrada(CategoriaId id) {
        return new CategoriaDaRubricaException("CATEGORIA_NAO_ENCONTRADA", "Categoria não encontrada",
                Tipo.NAO_ENCONTRADO, id);
    }

    static CategoriaDaRubricaException inativa(CategoriaId id) {
        return new CategoriaDaRubricaException("CATEGORIA_INATIVA", "A categoria está desativada e não recebe gastos",
                Tipo.VALIDACAO, id);
    }
}
