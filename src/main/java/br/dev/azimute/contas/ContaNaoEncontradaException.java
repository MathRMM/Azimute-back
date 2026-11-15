package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

final class ContaNaoEncontradaException extends ErroDeNegocio {

    ContaNaoEncontradaException(ContaId id) {
        super("CONTA_NAO_ENCONTRADA", "Conta não encontrada", Tipo.NAO_ENCONTRADO, Map.of("id", id.toString()));
    }
}
