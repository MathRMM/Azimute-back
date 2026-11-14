package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** O reenvio pela fila não ressuscita o que foi excluído (T9). */
final class MovimentacaoExcluidaException extends ErroDeNegocio {

    MovimentacaoExcluidaException(MovimentacaoId id) {
        super("MOVIMENTACAO_EXCLUIDA", "Essa movimentação foi excluída. Para voltar, restaure-a", Tipo.CONFLITO, Map.of("id", id.toString()));
    }
}
