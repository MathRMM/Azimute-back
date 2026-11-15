package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Mesmo formato da falha de Bean Validation, para regras checadas fora das anotações. */
final class DadosDaFolhaInvalidosException extends ErroDeNegocio {

    DadosDaFolhaInvalidosException(Map<String, Object> campos) {
        super("DADOS_INVALIDOS", "Há campos inválidos na requisição", Tipo.VALIDACAO, Map.of("campos", campos));
    }
}
