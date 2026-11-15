package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

final class SimulacaoNaoEncontradaException extends ErroDeNegocio {

    SimulacaoNaoEncontradaException(SimulacaoId id) {
        super("SIMULACAO_NAO_ENCONTRADA", "Simulação não encontrada", Tipo.NAO_ENCONTRADO, Map.of("id", id.toString()));
    }
}
