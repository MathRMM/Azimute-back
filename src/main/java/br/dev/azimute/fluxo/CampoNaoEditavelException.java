package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.List;
import java.util.Map;

/** O PATCH só altera os campos listados; os outros são nomeados no erro. */
final class CampoNaoEditavelException extends ErroDeNegocio {

    CampoNaoEditavelException(List<String> campos, String editaveis) {
        super("CAMPO_NAO_EDITAVEL", "Só " + editaveis + " podem ser alterados", Tipo.VALIDACAO,
                Map.of("campos", campos));
    }
}
