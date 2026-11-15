package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.List;
import java.util.Map;

/** A proposta é âncora de estimativas: excluí-la deixaria estimativas sem origem (decisão D3). */
final class SimulacaoEAncoraException extends ErroDeNegocio {

    SimulacaoEAncoraException(List<String> estimativas) {
        super("SIMULACAO_E_ANCORA",
                "Esta proposta é a base de estimativas salvas. Exclua as estimativas antes.",
                Tipo.CONFLITO, Map.of("estimativas", estimativas));
    }
}
