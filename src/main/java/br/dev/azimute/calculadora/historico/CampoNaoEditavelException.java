package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.List;
import java.util.Map;

/**
 * O PATCH só altera campos anotativos (RF23). Valores são imutáveis: se a proposta mudou, é outra proposta,
 * e vira registro novo — editar destruiria a comparação histórica.
 */
final class CampoNaoEditavelException extends ErroDeNegocio {

    CampoNaoEditavelException(List<String> campos) {
        super("CAMPO_NAO_EDITAVEL",
                "Só observação e status podem ser alterados. Para outros valores, registre uma nova simulação.",
                Tipo.VALIDACAO, Map.of("campos", campos));
    }
}
