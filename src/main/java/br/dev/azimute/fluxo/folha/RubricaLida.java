package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Dinheiro;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Uma linha do holerite como veio, sem interpretação: o valor está na coluna de vencimentos ou na de descontos,
 * nunca nas duas. A referência (horas, percentual, dias) é opcional.
 */
record RubricaLida(String codigo, String descricao, BigDecimal referencia, Dinheiro vencimento, Dinheiro desconto) {

    RubricaLida {
        Objects.requireNonNull(codigo, "codigo");
        if (codigo.isBlank()) {
            throw new IllegalArgumentException("Código da rubrica em branco");
        }
        Objects.requireNonNull(descricao, "descricao");
        if ((vencimento == null) == (desconto == null)) {
            throw new IllegalArgumentException("Rubrica " + codigo + " precisa de vencimento ou desconto, só um");
        }
    }
}
