package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** O reenvio pela fila não ressuscita uma simulação excluída (RF41). Para voltar, use o restaurar. */
final class SimulacaoExcluidaException extends ErroDeNegocio {

    SimulacaoExcluidaException(SimulacaoId id) {
        super("SIMULACAO_EXCLUIDA", "Essa simulação foi excluída. Para voltar, restaure-a", Tipo.CONFLITO,
                Map.of("id", id.toString()));
    }
}
