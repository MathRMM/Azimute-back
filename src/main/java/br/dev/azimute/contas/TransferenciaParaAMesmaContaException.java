package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RN04, do lado da aplicação: o banco também recusa (constraint direcao_coerente). */
final class TransferenciaParaAMesmaContaException extends ErroDeNegocio {

    TransferenciaParaAMesmaContaException(ContaId conta) {
        super("TRANSFERENCIA_PARA_A_MESMA_CONTA", "Origem e destino da transferência precisam ser contas diferentes", Tipo.VALIDACAO, Map.of("conta", conta.toString()));
    }
}
