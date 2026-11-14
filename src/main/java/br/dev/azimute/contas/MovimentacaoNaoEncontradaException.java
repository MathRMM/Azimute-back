package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Só no restaurar: excluir o que não existe não é erro (T3). */
final class MovimentacaoNaoEncontradaException extends ErroDeNegocio {

    MovimentacaoNaoEncontradaException(MovimentacaoId id) {
        super("MOVIMENTACAO_NAO_ENCONTRADA", "Movimentação não encontrada", Tipo.NAO_ENCONTRADO, Map.of("id", id.toString()));
    }
}
