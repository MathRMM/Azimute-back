package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeNegocio;
import br.dev.azimute.contas.ContaId;

import java.util.Map;

/** Mesmo código do erro de {@code contas}, para o cliente tratar igual. */
final class ContaDaFolhaNaoEncontradaException extends ErroDeNegocio {

    ContaDaFolhaNaoEncontradaException(ContaId id) {
        super("CONTA_NAO_ENCONTRADA", "Conta não encontrada", Tipo.NAO_ENCONTRADO, Map.of("id", id.toString()));
    }
}
