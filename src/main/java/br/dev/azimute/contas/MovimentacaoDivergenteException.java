package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Reenvio idêntico é rede ruim; reenvio diferente é bug do cliente e não deve ser escondido (P1). */
final class MovimentacaoDivergenteException extends ErroDeNegocio {

    MovimentacaoDivergenteException(MovimentacaoId id) {
        super("MOVIMENTACAO_DIVERGENTE", "Já existe uma movimentação com esse ID e dados diferentes", Tipo.CONFLITO, Map.of("id", id.toString()));
    }
}
