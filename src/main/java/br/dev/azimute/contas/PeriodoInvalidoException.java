package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.time.LocalDate;
import java.util.Map;

/** Extrato com período invertido (RF12). */
final class PeriodoInvalidoException extends ErroDeNegocio {

    PeriodoInvalidoException(LocalDate de, LocalDate ate) {
        super("PERIODO_INVALIDO", "A data inicial do período precisa ser igual ou anterior à final",
                Tipo.VALIDACAO, Map.of("de", de.toString(), "ate", ate.toString()));
    }
}
