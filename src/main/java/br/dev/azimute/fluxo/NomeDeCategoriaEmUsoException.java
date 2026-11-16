package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RN06: nome de categoria único, ignorando maiúsculas e espaços nas pontas. */
final class NomeDeCategoriaEmUsoException extends ErroDeNegocio {

    NomeDeCategoriaEmUsoException(String nome) {
        super("NOME_DE_CATEGORIA_EM_USO", "Já existe uma categoria com esse nome", Tipo.CONFLITO,
                Map.of("nome", nome));
    }
}
