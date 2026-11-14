package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Apelido é único ignorando maiúsculas e espaços nas pontas (RN09). */
final class ApelidoEmUsoException extends ErroDeNegocio {

    ApelidoEmUsoException(String apelido) {
        super("APELIDO_EM_USO", "Já existe uma conta com esse apelido", Tipo.CONFLITO, Map.of("apelido", apelido));
    }
}
