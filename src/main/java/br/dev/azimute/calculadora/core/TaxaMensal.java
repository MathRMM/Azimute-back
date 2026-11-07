package br.dev.azimute.calculadora.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * Taxa de juros ao mês, como fração: 3,51% é {@code 0.0351} (RF10).
 * <p>
 * Tipo próprio para evitar o erro mais comum do domínio: passar {@code 3.51} onde se esperava
 * {@code 0.0351}.
 */
public record TaxaMensal(BigDecimal fracao) {

    public static final TaxaMensal ZERO = new TaxaMensal(BigDecimal.ZERO);

    private static final MathContext MC = MathContext.DECIMAL64;

    public TaxaMensal {
        Objects.requireNonNull(fracao, "fracao");
        if (fracao.signum() < 0) {
            throw new ValorInvalidoException("taxa", "taxa negativa (" + fracao.toPlainString() + ")");
        }
    }

    /** {@code "3.51"} → {@code 0.0351}. */
    public static TaxaMensal dePercentual(String percentual) {
        return new TaxaMensal(new BigDecimal(percentual).movePointLeft(2));
    }

    /** {@code 0.0351} → {@code 3.51}. */
    public BigDecimal percentual() {
        return fracao.movePointRight(2);
    }

    /** Taxa anual equivalente, {@code (1+i)^12 − 1}, também como fração. */
    public TaxaMensal anualizada() {
        return new TaxaMensal(BigDecimal.ONE.add(fracao).pow(12, MC).subtract(BigDecimal.ONE, MC));
    }

    @Override
    public String toString() {
        return fracao.toPlainString();
    }
}
