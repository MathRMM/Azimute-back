package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RF34: um adiantamento antecipado por competência; o holerite associa um só. */
final class AdiantamentoJaLancadoException extends ErroDeNegocio {

    AdiantamentoJaLancadoException(Competencia competencia) {
        super("ADIANTAMENTO_JA_LANCADO", "Já há um adiantamento lançado nesta competência", Tipo.CONFLITO,
                Map.of("competencia", competencia.toString()));
    }
}
