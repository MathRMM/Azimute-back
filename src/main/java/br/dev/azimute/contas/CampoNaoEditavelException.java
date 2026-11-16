package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeCampoNaoEditavel;

import java.util.List;

/** O PATCH de conta só altera apelido, instituição, função, limite e ativa (RF04). */
final class CampoNaoEditavelException extends ErroDeCampoNaoEditavel {

    CampoNaoEditavelException(List<String> campos) {
        super(campos, "Só apelido, instituição, função, limite de cheque especial e ativa podem ser alterados");
    }
}
