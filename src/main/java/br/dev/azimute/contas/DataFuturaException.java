package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.time.LocalDate;
import java.util.Map;

/** O saldo real é lido de um extrato que já existe: conciliar uma data futura não faz sentido. */
final class DataFuturaException extends ErroDeNegocio {

    DataFuturaException(LocalDate data, LocalDate hoje) {
        super("DATA_FUTURA", "Não dá para conciliar uma data futura", Tipo.VALIDACAO,
                Map.of("data", data.toString(), "hoje", hoje.toString()));
    }
}
