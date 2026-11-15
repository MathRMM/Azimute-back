package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** T9: este mesmo holerite foi importado e excluído; não volta por reenvio, use o restaurar. */
final class HoleriteExcluidoException extends ErroDeNegocio {

    HoleriteExcluidoException(HoleriteId id) {
        super("HOLERITE_EXCLUIDO", "Este holerite foi excluído; restaure-o", Tipo.CONFLITO,
                Map.of("id", id.toString()));
    }
}
