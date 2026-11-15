package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RF30: a conta do FGTS é do tipo FGTS, a da previdência é PREVIDENCIA, e a do salário não é nenhuma das duas. */
final class TipoDeContaInvalidoException extends ErroDeNegocio {

    TipoDeContaInvalidoException(String campo, String esperado) {
        super("TIPO_DE_CONTA_INVALIDO", "A conta informada não é do tipo esperado", Tipo.VALIDACAO,
                Map.of("campo", campo, "esperado", esperado));
    }
}
