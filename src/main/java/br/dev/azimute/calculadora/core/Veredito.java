package br.dev.azimute.calculadora.core;

import br.dev.azimute.comum.Dinheiro;

/**
 * Resultado de "financiar e deixar o dinheiro rendendo: saio na frente ou atrás?" (RF15).
 * <p>
 * O delta vem em reais, não como booleano: "financiar custa R$ 8.340 a mais" é argumento de negociação;
 * "não vale a pena" não é.
 *
 * @param custoTotalFinanciado         entrada + parcela × prazo
 * @param custoTotalAVista             o preço à vista
 * @param rendimentoDoDinheiroGuardado quanto o dinheiro não gasto à vista rende enquanto as parcelas são pagas
 * @param delta                        positivo = à vista vence; negativo = financiar vence
 * @param taxaFinanciamento            taxa efetiva das parcelas (zero se não houver juros)
 * @param taxaRendimento               a taxa de rendimento informada
 */
public record Veredito(
        Dinheiro custoTotalFinanciado,
        Dinheiro custoTotalAVista,
        Dinheiro rendimentoDoDinheiroGuardado,
        Dinheiro delta,
        TaxaMensal taxaFinanciamento,
        TaxaMensal taxaRendimento) {

    public boolean aVistaVence() {
        return delta.positivo();
    }
}
