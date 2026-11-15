package br.dev.azimute.calculadora.core;

import br.dev.azimute.comum.Dinheiro;

import java.math.BigDecimal;

/**
 * Uma posição do slider de entrada, com a taxa da proposta real mantida fixa (RF16).
 *
 * @param distanciaDaAncora {@code |entrada − entradaAncora| ÷ valorBem}, 4 casas (decisão T7). Zero na
 *                          proposta real, o único ponto onde o número é fato; cresce com a extrapolação.
 *                          É o insumo para a tela degradar a confiança visual da estimativa.
 */
public record PontoSensibilidade(
        Dinheiro entrada,
        Dinheiro financiado,
        Dinheiro parcela,
        Dinheiro totalPago,
        Dinheiro totalJuros,
        BigDecimal distanciaDaAncora) {

    public boolean ancora() {
        return distanciaDaAncora.signum() == 0;
    }
}
