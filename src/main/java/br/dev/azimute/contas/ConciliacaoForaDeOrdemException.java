package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.time.LocalDate;
import java.util.Map;

/**
 * A data da conciliação precisa ser posterior à da última conciliação da conta (P3). Uma conciliação errada se
 * corrige com outra num dia seguinte, nunca editando a anterior (RN07).
 */
final class ConciliacaoForaDeOrdemException extends ErroDeNegocio {

    ConciliacaoForaDeOrdemException(LocalDate ultimaConciliacao) {
        super("CONCILIACAO_FORA_DE_ORDEM",
                "A data precisa ser posterior à da última conciliação desta conta",
                Tipo.VALIDACAO, Map.of("ultimaConciliacao", ultimaConciliacao.toString()));
    }
}
