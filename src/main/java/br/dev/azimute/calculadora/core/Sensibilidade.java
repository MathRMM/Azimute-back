package br.dev.azimute.calculadora.core;

import br.dev.azimute.comum.Dinheiro;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Curva de sensibilidade à entrada: o slider da calculadora (RF16, decisão T7).
 * <p>
 * Segurar a taxa constante enquanto a entrada varia é aproximação local, e ela erra nos dois sentidos:
 * financeira dá taxa melhor com entrada maior (estimativa pessimista), e tarifas fixas pesam mais sobre
 * financiado menor (estimativa otimista). Por isso cada ponto carrega a distância à âncora. Não se tenta
 * modelar a variação da taxa: a política de crédito da financeira não é pública.
 */
public final class Sensibilidade {

    private static final int ESCALA_DISTANCIA = 4;

    private Sensibilidade() {}

    /**
     * {@code passos} pontos igualmente espaçados com entrada de R$ 0 até o valor do bem inteiro (o último é o
     * cenário à vista), mais o ponto exato da âncora, sem duplicá-lo. Ordenados por entrada.
     */
    public static List<PontoSensibilidade> curvaPorEntrada(
            Dinheiro valorBem, Dinheiro entradaAncora, TaxaMensal taxa, int prazo, int passos) {

        if (!valorBem.positivo()) throw new ValorInvalidoException("valor do bem", "deve ser maior que zero");
        if (entradaAncora.negativo()) throw new ValorInvalidoException("entrada da proposta", "não pode ser negativa");
        if (entradaAncora.compareTo(valorBem) >= 0) {
            throw new ValorInvalidoException("entrada da proposta", "deve ser menor que o valor do bem");
        }
        if (prazo < 1) throw new ValorInvalidoException("prazo", "deve ser de pelo menos 1 mês");
        if (passos < 2) throw new ValorInvalidoException("passos", "a curva precisa de pelo menos 2 pontos");

        var entradas = new TreeSet<Dinheiro>();
        var intervalos = BigDecimal.valueOf(passos - 1L);
        for (int k = 0; k < passos; k++) {
            entradas.add(new Dinheiro(valorBem.valor().multiply(BigDecimal.valueOf(k))
                    .divide(intervalos, Dinheiro.ESCALA, Dinheiro.ARREDONDAMENTO)));
        }
        entradas.add(entradaAncora);

        var pontos = new ArrayList<PontoSensibilidade>(entradas.size());
        for (var entrada : entradas) {
            pontos.add(ponto(valorBem, entradaAncora, taxa, prazo, entrada));
        }
        return List.copyOf(pontos);
    }

    private static PontoSensibilidade ponto(
            Dinheiro valorBem, Dinheiro entradaAncora, TaxaMensal taxa, int prazo, Dinheiro entrada) {

        var financiado = valorBem.menos(entrada);
        var parcela = JurosCompostos.parcela(financiado, taxa, prazo);
        var totalPago = parcela.vezes(prazo);
        var distancia = entrada.menos(entradaAncora).valor().abs()
                .divide(valorBem.valor(), ESCALA_DISTANCIA, RoundingMode.HALF_EVEN);

        return new PontoSensibilidade(entrada, financiado, parcela, totalPago, totalPago.menos(financiado), distancia);
    }
}
