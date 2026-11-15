package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RF35a, T7: uma regra por vigência, e regra não se altera: registre outra com vigência nova. */
final class RegraDeContrapartidaExistenteException extends ErroDeNegocio {

    RegraDeContrapartidaExistenteException(Competencia aPartirDe) {
        super("REGRA_DE_CONTRAPARTIDA_EXISTENTE", "Já existe uma regra de contrapartida com essa vigência",
                Tipo.CONFLITO, Map.of("aPartirDe", aPartirDe.toString()));
    }
}
