package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Mesmo formato da falha de Bean Validation, para regras checadas fora das anotações. */
final class DadosInvalidosException extends ErroDeNegocio {

    DadosInvalidosException(Map<String, Object> campos) {
        super("DADOS_INVALIDOS", "Há campos inválidos na requisição", Tipo.VALIDACAO, Map.of("campos", campos));
    }
}
