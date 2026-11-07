package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** O ID já existe com outros valores: reenvio com corpo diferente é bug do cliente (decisão D2). */
final class SimulacaoDivergenteException extends ErroDeNegocio {

    SimulacaoDivergenteException(SimulacaoId id) {
        super("SIMULACAO_DIVERGENTE",
                "Já existe uma simulação com este ID e valores diferentes",
                Tipo.CONFLITO, Map.of("id", id.toString()));
    }
}
