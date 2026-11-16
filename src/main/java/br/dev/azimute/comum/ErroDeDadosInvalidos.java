package br.dev.azimute.comum;

import java.util.Map;

/**
 * Base dos erros de campo inválido checados fora das anotações, no mesmo formato da falha de Bean Validation:
 * {@code DADOS_INVALIDOS} com {@code detalhes.campos}. Cada módulo estende com a sua classe, para o erro continuar
 * sendo dele; o código e o formato ficam só aqui.
 */
public abstract class ErroDeDadosInvalidos extends ErroDeNegocio {

    protected ErroDeDadosInvalidos(Map<String, Object> campos) {
        super("DADOS_INVALIDOS", "Há campos inválidos na requisição", Tipo.VALIDACAO, Map.of("campos", campos));
    }
}
