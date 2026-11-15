package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Entrada, saída e pagamento de fatura nascem em fluxo ou cartoes (T2). */
final class MovimentacaoDeOutroModuloException extends ErroDeNegocio {

    MovimentacaoDeOutroModuloException(MovimentacaoId id) {
        super("MOVIMENTACAO_DE_OUTRO_MODULO", "Essa movimentação pertence a outro módulo e não pode ser alterada por aqui", Tipo.VALIDACAO, Map.of("id", id.toString()));
    }
}
