package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Quanto a empresa põe na previdência, como fração da básica, a partir de uma competência (RF35a, RN19).
 * Regras só são inseridas: a nova vale dali em diante e não muda holerites antigos (T7).
 */
record RegraContrapartida(Competencia aPartirDe, BigDecimal percentualDaBasica) {

    RegraContrapartida {
        Objects.requireNonNull(aPartirDe, "aPartirDe");
        Objects.requireNonNull(percentualDaBasica, "percentualDaBasica");
        if (percentualDaBasica.signum() < 0) {
            throw new IllegalArgumentException("Percentual da básica negativo: " + percentualDaBasica);
        }
    }
}
