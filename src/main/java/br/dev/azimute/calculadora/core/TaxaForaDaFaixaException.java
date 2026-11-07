package br.dev.azimute.calculadora.core;

import br.dev.azimute.comum.ErroDeNegocio;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A taxa implícita está fora de [0,0001%; 30%] ao mês (decisão D5). Um resultado errado é pior que um
 * erro explícito.
 */
public final class TaxaForaDaFaixaException extends ErroDeNegocio {

    public TaxaForaDaFaixaException(BigDecimal minimo, BigDecimal maximo, boolean acima) {
        super("TAXA_FORA_DA_FAIXA",
                acima ? "A taxa das parcelas passa de 30% ao mês" : "A taxa das parcelas é menor que 0,0001% ao mês",
                Tipo.VALIDACAO, Map.of(
                        "minimo", minimo.toPlainString(),
                        "maximo", maximo.toPlainString(),
                        "limite", acima ? "MAXIMO" : "MINIMO"));
    }
}
