package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.List;
import java.util.Map;

/** O PATCH de conta só altera apelido, instituição, função, limite e ativa (RF04). */
final class CampoNaoEditavelException extends ErroDeNegocio {

    CampoNaoEditavelException(List<String> campos) {
        super("CAMPO_NAO_EDITAVEL",
                "Só apelido, instituição, função, limite de cheque especial e ativa podem ser alterados",
                Tipo.VALIDACAO, Map.of("campos", campos));
    }
}
