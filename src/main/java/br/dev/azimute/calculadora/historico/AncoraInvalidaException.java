package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Estimativa sem âncora, com âncora inexistente ou ancorada em algo que não é proposta (RN11). */
final class AncoraInvalidaException extends ErroDeNegocio {

    AncoraInvalidaException(String motivo) {
        super("ANCORA_INVALIDA", motivo, Tipo.VALIDACAO, Map.of());
    }
}
