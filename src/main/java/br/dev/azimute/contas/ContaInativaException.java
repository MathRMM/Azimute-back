package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Conta inativa continua no histórico, mas não recebe nada novo (RN08). */
final class ContaInativaException extends ErroDeNegocio {

    ContaInativaException(ContaId conta) {
        super("CONTA_INATIVA", "A conta está desativada e não recebe movimentações nem conciliações", Tipo.VALIDACAO, Map.of("conta", conta.toString()));
    }
}
