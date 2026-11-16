package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeDadosInvalidos;

import java.util.Map;

/** Regras da folha checadas fora das anotações. */
final class DadosDaFolhaInvalidosException extends ErroDeDadosInvalidos {

    DadosDaFolhaInvalidosException(Map<String, Object> campos) {
        super(campos);
    }
}
