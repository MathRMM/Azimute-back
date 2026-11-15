package br.dev.azimute.comum;

import java.math.BigDecimal;

/**
 * Percentual inteiro de 0 a 100 (RF03, RN02). Inteiro de propósito: garante que alocações somem
 * exatamente 100, sem erro de arredondamento.
 */
public record Percentual(int pontos) {

    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    public Percentual {
        if (pontos < 0 || pontos > 100) {
            throw new IllegalArgumentException("Percentual fora de 0–100: " + pontos);
        }
    }

    /** 45% → 0.45. Exato: toda divisão de inteiro por 100 tem representação decimal finita. */
    public BigDecimal fracao() {
        return BigDecimal.valueOf(pontos).divide(CEM);
    }

    @Override
    public String toString() {
        return pontos + "%";
    }
}
