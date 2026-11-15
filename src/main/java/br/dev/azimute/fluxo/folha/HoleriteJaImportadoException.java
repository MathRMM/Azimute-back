package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RN11, T9: já há um holerite vivo, com outro conteúdo, na mesma competência. Exclua o anterior antes. */
final class HoleriteJaImportadoException extends ErroDeNegocio {

    HoleriteJaImportadoException(Competencia competencia) {
        super("HOLERITE_JA_IMPORTADO", "Já existe um holerite nesta competência; exclua-o antes", Tipo.CONFLITO,
                Map.of("competencia", competencia.toString()));
    }
}
