package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Competência fora do formato {@code AAAA-MM} num parâmetro de consulta. */
final class CompetenciaInvalidaException extends ErroDeNegocio {

    CompetenciaInvalidaException(String texto) {
        super("COMPETENCIA_INVALIDA", "Competência deve estar no formato AAAA-MM", Tipo.VALIDACAO,
                Map.of("competencia", String.valueOf(texto)));
    }
}
