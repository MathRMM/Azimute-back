package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.ErroDeDadosInvalidos;

import java.util.Map;

/** Regras de valor da simulação (RN10). */
final class DadosInvalidosException extends ErroDeDadosInvalidos {

    DadosInvalidosException(Map<String, Object> campos) {
        super(campos);
    }
}
