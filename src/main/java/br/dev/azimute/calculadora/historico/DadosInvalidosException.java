package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Regras de valor da simulação (RN10), no mesmo formato da falha de Bean Validation. */
final class DadosInvalidosException extends ErroDeNegocio {

    DadosInvalidosException(Map<String, Object> campos) {
        super("DADOS_INVALIDOS", "Há campos inválidos na requisição", Tipo.VALIDACAO, Map.of("campos", campos));
    }
}
