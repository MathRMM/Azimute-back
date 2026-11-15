package br.dev.azimute.calculadora.core;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Entrada fora do domínio da operação: valor zero ou negativo, prazo inválido (RN04). */
public final class ValorInvalidoException extends ErroDeNegocio {

    public ValorInvalidoException(String campo, String motivo) {
        super("VALOR_INVALIDO", "Valor inválido para " + campo + ": " + motivo,
                Tipo.VALIDACAO, Map.of("campo", campo));
    }
}
