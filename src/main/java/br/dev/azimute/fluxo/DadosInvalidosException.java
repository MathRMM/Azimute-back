package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeDadosInvalidos;

import java.util.Map;

/** Regras do fluxo checadas fora das anotações. */
final class DadosInvalidosException extends ErroDeDadosInvalidos {

    DadosInvalidosException(Map<String, Object> campos) {
        super(campos);
    }
}
