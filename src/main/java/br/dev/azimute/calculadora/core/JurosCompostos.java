package br.dev.azimute.calculadora.core;

import br.dev.azimute.comum.Dinheiro;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Matemática financeira da tabela Price (RF11–RF14). Função pura: sem estado, sem I/O, sem Spring.
 * <p>
 * Espelhada no app Kotlin, que calcula offline na concessionária. As duas implementações são travadas
 * pelos vetores de ouro ({@code vetores-juros.json}).
 * <p>
 * Cálculos intermediários em {@link MathContext#DECIMAL64}; o arredondamento a centavos acontece só no
 * {@link Dinheiro} final (RN06). Arredondar no meio do laço destrói a convergência.
 */
public final class JurosCompostos {

    /** Faixa da bisseção: de 0,0001% a 30% ao mês, cobrindo de poupança a agiotagem. */
    public static final BigDecimal TAXA_MINIMA = new BigDecimal("0.000001");
    public static final BigDecimal TAXA_MAXIMA = new BigDecimal("0.30");

    static final MathContext MC = MathContext.DECIMAL64;

    /** Diferença em reais entre o valor presente calculado e o financiado que encerra a busca. */
    private static final BigDecimal TOLERANCIA_VALOR = new BigDecimal("1E-10");
    /** Largura do intervalo de taxa que encerra a busca (DECIMAL64 limita a precisão do valor). */
    private static final BigDecimal TOLERANCIA_TAXA = new BigDecimal("1E-12");
    private static final int MAX_ITERACOES = 200;
    private static final BigDecimal DOIS = BigDecimal.valueOf(2);

    private JurosCompostos() {}

    /** Quanto {@code pv} vira após {@code n} meses rendendo {@code i} ao mês. */
    public static Dinheiro montante(Dinheiro pv, TaxaMensal i, int n) {
        exigirNaoNegativo("valor", pv);
        if (n < 0) throw new ValorInvalidoException("prazo", "prazo negativo");
        return new Dinheiro(pv.valor().multiply(BigDecimal.ONE.add(i.fracao()).pow(n, MC), MC));
    }

    /** Parcela de um financiamento de {@code pv} em {@code n} meses à taxa {@code i} (Price). */
    public static Dinheiro parcela(Dinheiro pv, TaxaMensal i, int n) {
        exigirNaoNegativo("valor", pv);
        exigirPrazo(n);
        return new Dinheiro(parcelaExata(pv.valor(), i.fracao(), n));
    }

    /** Quanto vale hoje um fluxo de {@code n} parcelas de {@code pmt}, descontado a {@code i}. */
    public static Dinheiro valorPresente(Dinheiro pmt, TaxaMensal i, int n) {
        exigirNaoNegativo("parcela", pmt);
        if (n < 0) throw new ValorInvalidoException("prazo", "prazo negativo");
        return new Dinheiro(valorPresenteExato(pmt.valor(), i.fracao(), n));
    }

    /**
     * A taxa embutida numa proposta: {@code PV = PMT × [1 − (1+i)^−n] / i}, resolvida por bisseção.
     * <p>
     * Bisseção e não Newton-Raphson: converge mais devagar, mas nunca diverge — e um resultado errado na
     * concessionária é pior que um resultado 30 ms mais lento.
     *
     * @throws ValorInvalidoException   se {@code pv}, {@code pmt} ou {@code n} não forem positivos
     * @throws SemJurosException        se {@code pmt × n ≤ pv}
     * @throws TaxaForaDaFaixaException se a taxa estiver fora de [{@link #TAXA_MINIMA}; {@link #TAXA_MAXIMA}]
     */
    public static TaxaMensal taxaImplicita(Dinheiro pv, Dinheiro pmt, int n) {
        exigirPositivo("valor financiado", pv);
        exigirPositivo("parcela", pmt);
        exigirPrazo(n);
        if (pmt.vezes(n).compareTo(pv) <= 0) {
            throw new SemJurosException(pmt.vezes(n), pv);
        }
        return new TaxaMensal(taxaImplicitaExata(pv.valor(), pmt.valor(), n));
    }

    // ---- precisão total (sem arredondar a centavos) — usadas pelo próprio core e pelos testes ----

    static BigDecimal parcelaExata(BigDecimal pv, BigDecimal i, int n) {
        if (i.signum() == 0) {
            return pv.divide(BigDecimal.valueOf(n), MC);
        }
        return pv.multiply(i, MC).divide(BigDecimal.ONE.subtract(descontoComposto(i, n), MC), MC);
    }

    static BigDecimal valorPresenteExato(BigDecimal pmt, BigDecimal i, int n) {
        if (i.signum() == 0) {
            return pmt.multiply(BigDecimal.valueOf(n), MC);
        }
        return pmt.multiply(BigDecimal.ONE.subtract(descontoComposto(i, n), MC), MC).divide(i, MC);
    }

    /** Pressupõe {@code pv > 0}, {@code pmt > 0}, {@code n ≥ 1} e {@code pmt × n > pv}. */
    static BigDecimal taxaImplicitaExata(BigDecimal pv, BigDecimal pmt, int n) {
        if (n == 1) {
            // Solução fechada: PV = PMT / (1+i)
            return dentroDaFaixa(pmt.divide(pv, MC).subtract(BigDecimal.ONE, MC));
        }

        // O valor presente decresce com a taxa: fora da faixa, não há troca de sinal no intervalo.
        if (valorPresenteExato(pmt, TAXA_MAXIMA, n).compareTo(pv) > 0) {
            throw foraDaFaixa(true);
        }
        if (valorPresenteExato(pmt, TAXA_MINIMA, n).compareTo(pv) < 0) {
            throw foraDaFaixa(false);
        }

        var baixo = TAXA_MINIMA;
        var alto = TAXA_MAXIMA;
        for (int k = 0; k < MAX_ITERACOES; k++) {
            var meio = baixo.add(alto).divide(DOIS, MC);
            var vp = valorPresenteExato(pmt, meio, n);

            if (vp.subtract(pv).abs().compareTo(TOLERANCIA_VALOR) < 0
                    || alto.subtract(baixo).compareTo(TOLERANCIA_TAXA) < 0) {
                return meio;
            }
            if (vp.compareTo(pv) > 0) baixo = meio; else alto = meio;
        }
        throw new NaoConvergiuException(MAX_ITERACOES);
    }

    /** {@code (1+i)^−n}. */
    private static BigDecimal descontoComposto(BigDecimal i, int n) {
        return BigDecimal.ONE.add(i).pow(-n, MC);
    }

    private static BigDecimal dentroDaFaixa(BigDecimal taxa) {
        if (taxa.compareTo(TAXA_MAXIMA) > 0) throw foraDaFaixa(true);
        if (taxa.compareTo(TAXA_MINIMA) < 0) throw foraDaFaixa(false);
        return taxa;
    }

    private static TaxaForaDaFaixaException foraDaFaixa(boolean acima) {
        return new TaxaForaDaFaixaException(TAXA_MINIMA, TAXA_MAXIMA, acima);
    }

    private static void exigirPositivo(String campo, Dinheiro valor) {
        if (!valor.positivo()) throw new ValorInvalidoException(campo, "deve ser maior que zero");
    }

    private static void exigirNaoNegativo(String campo, Dinheiro valor) {
        if (valor.negativo()) throw new ValorInvalidoException(campo, "não pode ser negativo");
    }

    private static void exigirPrazo(int n) {
        if (n < 1) throw new ValorInvalidoException("prazo", "deve ser de pelo menos 1 mês");
    }
}
