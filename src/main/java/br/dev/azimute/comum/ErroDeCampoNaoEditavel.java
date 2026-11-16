package br.dev.azimute.comum;

import java.util.List;
import java.util.Map;

/**
 * Base do erro de PATCH com campo que não pode ser alterado: {@code CAMPO_NAO_EDITAVEL} com {@code detalhes.campos}.
 * A mensagem é do módulo, porque só ele sabe dizer o que pode ser alterado e por quê.
 */
public abstract class ErroDeCampoNaoEditavel extends ErroDeNegocio {

    protected ErroDeCampoNaoEditavel(List<String> campos, String mensagem) {
        super("CAMPO_NAO_EDITAVEL", mensagem, Tipo.VALIDACAO, Map.of("campos", campos));
    }
}
