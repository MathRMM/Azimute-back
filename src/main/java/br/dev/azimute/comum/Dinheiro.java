package br.dev.azimute.comum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valor monetário em reais. {@code BigDecimal} com escala 2 e arredondamento bancário (RF01, RN01).
 * <p>
 * {@code HALF_EVEN} em vez de {@code HALF_UP}: sobre muitas operações, {@code HALF_UP} enviesa para cima.
 * No banco: {@code NUMERIC(19,2)}, nunca {@code float8} ou {@code money}.
 */
public record Dinheiro(BigDecimal valor) implements Comparable<Dinheiro> {

    public static final int ESCALA = 2;
    public static final RoundingMode ARREDONDAMENTO = RoundingMode.HALF_EVEN;

    public static final Dinheiro ZERO = new Dinheiro(BigDecimal.ZERO);

    public Dinheiro {
        Objects.requireNonNull(valor, "valor");
        valor = valor.setScale(ESCALA, ARREDONDAMENTO);
    }

    /** @throws NumberFormatException se o texto não for um decimal válido */
    public static Dinheiro de(String valor) {
        return new Dinheiro(new BigDecimal(valor));
    }

    /**
     * Para o que vem de fora (corpo de requisição): aceita no máximo 2 casas significativas e nunca arredonda.
     * {@code "10.50"} e {@code "10.500"} passam; {@code "10.505"} é recusado. Espaços nas pontas são ignorados.
     *
     * @throws IllegalArgumentException se o texto for nulo, não for um decimal ou tiver mais de 2 casas
     */
    public static Dinheiro exato(String texto) {
        if (texto == null) {
            throw new IllegalArgumentException("Valor ausente");
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(texto.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor não é um decimal: " + texto, e);
        }
        if (decimal.stripTrailingZeros().scale() > ESCALA) {
            throw new IllegalArgumentException("Valor com mais de " + ESCALA + " casas decimais: " + texto);
        }
        return new Dinheiro(decimal);
    }

    public Dinheiro mais(Dinheiro outro) {
        return new Dinheiro(valor.add(outro.valor));
    }

    public Dinheiro menos(Dinheiro outro) {
        return new Dinheiro(valor.subtract(outro.valor));
    }

    public Dinheiro vezes(int fator) {
        return new Dinheiro(valor.multiply(BigDecimal.valueOf(fator)));
    }

    public boolean negativo() {
        return valor.signum() < 0;
    }

    public boolean positivo() {
        return valor.signum() > 0;
    }

    @Override
    public int compareTo(Dinheiro outro) {
        return valor.compareTo(outro.valor);
    }

    @Override
    public String toString() {
        return valor.toPlainString();
    }
}
