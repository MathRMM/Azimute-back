package br.dev.azimute.calculadora.core;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** A bisseção esgotou as iterações sem atingir a tolerância. Não deve ocorrer dentro da faixa. */
public final class NaoConvergiuException extends ErroDeNegocio {

    public NaoConvergiuException(int iteracoes) {
        super("NAO_CONVERGIU", "Não foi possível calcular a taxa das parcelas",
                Tipo.VALIDACAO, Map.of("iteracoes", iteracoes));
    }
}
