package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** ID excluído não volta por reenvio: use o restaurar (mesmo comportamento do T9 de contas). */
final class GastoExcluidoException extends ErroDeNegocio {

    GastoExcluidoException(GastoId id) {
        super("GASTO_EXCLUIDO", "O gasto com esse ID foi excluído; restaure-o", Tipo.CONFLITO,
                Map.of("id", id.toString()));
    }
}
