package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;
import java.util.UUID;

/**
 * Nenhuma entrada ou saída viva com essa referência de outro módulo (RF42 do PRD do fluxo). Vale para alterar e
 * restaurar; excluir o que não existe não é erro (T3).
 */
final class LancamentoNaoEncontradoException extends ErroDeNegocio {

    LancamentoNaoEncontradoException(UUID referencia) {
        super("LANCAMENTO_NAO_ENCONTRADO", "Lançamento não encontrado", Tipo.NAO_ENCONTRADO,
                Map.of("referencia", referencia.toString()));
    }
}
