package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** ID excluído não volta por reenvio: use o restaurar. */
final class GanhoExcluidoException extends ErroDeNegocio {

    GanhoExcluidoException(GanhoId id) {
        super("GANHO_EXCLUIDO", "O ganho com esse ID foi excluído; restaure-o", Tipo.CONFLITO,
                Map.of("id", id.toString()));
    }
}
