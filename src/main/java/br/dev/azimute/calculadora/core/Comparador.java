package br.dev.azimute.calculadora.core;

import br.dev.azimute.comum.Dinheiro;

/**
 * À vista × financiado com o dinheiro rendendo (RF15, decisão D7 do PRD).
 * <p>
 * Traz as parcelas a valor presente pela taxa do rendimento: é exatamente "financiar e deixar o dinheiro
 * aplicado enquanto pago". Com financiamento a 4,14% e CDB a 0,8%, à vista vence; numa taxa promocional
 * de 0,6%, a conta inverte.
 */
public final class Comparador {

    private Comparador() {}

    public static Veredito compararAVistaVsFinanciado(
            Dinheiro precoAVista, Dinheiro entrada, Dinheiro parcela, int prazo, TaxaMensal rendimento) {

        if (!precoAVista.positivo()) throw new ValorInvalidoException("preço à vista", "deve ser maior que zero");
        if (entrada.negativo()) throw new ValorInvalidoException("entrada", "não pode ser negativa");
        if (entrada.compareTo(precoAVista) >= 0) {
            throw new ValorInvalidoException("entrada", "deve ser menor que o preço à vista");
        }
        if (!parcela.positivo()) throw new ValorInvalidoException("parcela", "deve ser maior que zero");
        if (prazo < 1) throw new ValorInvalidoException("prazo", "deve ser de pelo menos 1 mês");

        var totalDasParcelas = parcela.vezes(prazo);
        var custoTotalFinanciado = entrada.mais(totalDasParcelas);
        var rendimentoDoDinheiroGuardado =
                totalDasParcelas.menos(JurosCompostos.valorPresente(parcela, rendimento, prazo));
        var delta = custoTotalFinanciado.menos(rendimentoDoDinheiroGuardado).menos(precoAVista);

        return new Veredito(
                custoTotalFinanciado,
                precoAVista,
                rendimentoDoDinheiroGuardado,
                delta,
                taxaDoFinanciamento(precoAVista.menos(entrada), parcela, prazo),
                rendimento);
    }

    /** Sem juros (ou abaixo de 0,0001% a.m.), a taxa é zero; acima de 30% a.m., o erro se propaga (D5). */
    private static TaxaMensal taxaDoFinanciamento(Dinheiro financiado, Dinheiro parcela, int prazo) {
        if (parcela.vezes(prazo).compareTo(financiado) <= 0) {
            return TaxaMensal.ZERO;
        }
        try {
            return JurosCompostos.taxaImplicita(financiado, parcela, prazo);
        } catch (TaxaForaDaFaixaException e) {
            if ("MINIMO".equals(e.detalhes().get("limite"))) {
                return TaxaMensal.ZERO;
            }
            throw e;
        }
    }
}
