package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RN02: o líquido de um ganho não pode superar o bruto. */
final class LiquidoMaiorQueBrutoException extends ErroDeNegocio {

    LiquidoMaiorQueBrutoException(Dinheiro bruto, Dinheiro liquido) {
        super("LIQUIDO_MAIOR_QUE_BRUTO", "O líquido não pode ser maior que o bruto", Tipo.VALIDACAO,
                Map.of("bruto", bruto.toString(), "liquido", liquido.toString()));
    }
}
