package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeCampoNaoEditavel;

import java.util.List;

/** O PATCH só altera os campos listados; os outros são nomeados no erro. */
final class CampoNaoEditavelException extends ErroDeCampoNaoEditavel {

    CampoNaoEditavelException(List<String> campos, String editaveis) {
        super(campos, "Só " + editaveis + " podem ser alterados");
    }
}
