package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.ErroDeCampoNaoEditavel;

import java.util.List;

/**
 * O PATCH só altera campos anotativos (RF23). Valores são imutáveis: se a proposta mudou, é outra proposta,
 * e vira registro novo — editar destruiria a comparação histórica.
 */
final class CampoNaoEditavelException extends ErroDeCampoNaoEditavel {

    CampoNaoEditavelException(List<String> campos) {
        super(campos, "Só observação e status podem ser alterados. Para outros valores, registre uma nova simulação.");
    }
}
