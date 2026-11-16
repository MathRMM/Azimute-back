package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RF37: o holerite lançado à mão não tem PDF. */
final class PdfNaoEncontradoException extends ErroDeNegocio {

    PdfNaoEncontradoException(HoleriteId id) {
        super("PDF_NAO_ENCONTRADO", "Este holerite não tem PDF: foi lançado à mão", Tipo.NAO_ENCONTRADO,
                Map.of("id", id.toString()));
    }
}
