package br.dev.azimute.calculadora.core;

import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** O total das parcelas não supera o valor financiado: não há juros a encontrar (RN04). */
public final class SemJurosException extends ErroDeNegocio {

    public SemJurosException(Dinheiro totalDasParcelas, Dinheiro financiado) {
        super("SEM_JUROS", "Total das parcelas não supera o valor financiado",
                Tipo.VALIDACAO, Map.of(
                        "totalDasParcelas", totalDasParcelas.toString(),
                        "financiado", financiado.toString()));
    }
}
