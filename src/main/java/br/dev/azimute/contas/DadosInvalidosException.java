package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeDadosInvalidos;

import java.util.Map;

/** Regras de contas checadas fora das anotações. */
final class DadosInvalidosException extends ErroDeDadosInvalidos {

    DadosInvalidosException(Map<String, Object> campos) {
        super(campos);
    }
}
