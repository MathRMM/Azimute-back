package br.dev.azimute.comum;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Taxa decimal, como a contrapartida da previdência: 75,5% é a fração {@code 0.755}.
 * <p>
 * Guarda a fração sem arredondar; a escala fica a cargo de quem grava. Nasce por {@link #dePontos} (o número que a
 * pessoa digita) ou {@link #deFracao} (o que o banco guarda), para nunca confundir {@code 75.5} com {@code 0.755}.
 * Para alocações que precisam somar exatamente 100, use {@link Percentual}, que é inteiro de propósito.
 */
public record Taxa(BigDecimal fracao) implements Comparable<Taxa> {

    public static final Taxa ZERO = new Taxa(BigDecimal.ZERO);

    public Taxa {
        Objects.requireNonNull(fracao, "fracao");
        if (fracao.signum() < 0) {
            throw new IllegalArgumentException("Taxa negativa: " + fracao.toPlainString());
        }
        // Mesma taxa, mesma representação: 0.7550 e 0.755 são iguais. Tirar zeros à direita não arredonda.
        fracao = fracao.stripTrailingZeros();
        if (fracao.scale() < 0) {
            fracao = fracao.setScale(0);
        }
    }

    /** {@code 75.50} → fração {@code 0.755}. */
    public static Taxa dePontos(BigDecimal pontos) {
        return new Taxa(Objects.requireNonNull(pontos, "pontos").movePointLeft(2));
    }

    /**
     * {@code "75.50"} → fração {@code 0.755}. Aceita espaços nas pontas; a vírgula não é separador decimal.
     *
     * @throws IllegalArgumentException se o texto for nulo, não for um decimal ou for negativo
     */
    public static Taxa dePontos(String pontos) {
        if (pontos == null) {
            throw new IllegalArgumentException("Taxa ausente");
        }
        try {
            return dePontos(new BigDecimal(pontos.strip()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Taxa não é um decimal: " + pontos, e);
        }
    }

    public static Taxa deFracao(BigDecimal fracao) {
        return new Taxa(fracao);
    }

    /** Fração em pontos percentuais: {@code 0.755} → {@code 75.5}. */
    public BigDecimal pontos() {
        return fracao.movePointRight(2);
    }

    @Override
    public int compareTo(Taxa outra) {
        return fracao.compareTo(outra.fracao);
    }

    @Override
    public String toString() {
        return pontos().toPlainString() + "%";
    }
}
