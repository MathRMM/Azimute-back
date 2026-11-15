package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Cheque especial só existe em conta corrente (RN11, T7). */
final class LimiteSoEmContaCorrenteException extends ErroDeNegocio {

    LimiteSoEmContaCorrenteException(TipoConta tipo) {
        super("LIMITE_SO_EM_CONTA_CORRENTE", "Limite de cheque especial só pode ser informado em conta corrente",
                Tipo.VALIDACAO, Map.of("tipo", tipo.name()));
    }
}
