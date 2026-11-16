package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.comum.Taxa;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Quanto a empresa põe na previdência, como fração da básica, a partir de uma competência (RF35a, RN19).
 * Regras só são inseridas: a nova vale dali em diante e não muda holerites antigos (T7).
 */
record RegraContrapartida(Competencia aPartirDe, Taxa percentualDaBasica) {

    RegraContrapartida {
        Objects.requireNonNull(aPartirDe, "aPartirDe");
        Objects.requireNonNull(percentualDaBasica, "percentualDaBasica");
    }

    /** A regra de maior início até a competência; sem nenhuma, contrapartida zero. */
    static RegraContrapartida vigente(List<RegraContrapartida> regras, Competencia competencia) {
        return regras.stream()
                .filter(r -> r.aPartirDe().compareTo(competencia) <= 0)
                .max(Comparator.comparing(RegraContrapartida::aPartirDe))
                .orElse(new RegraContrapartida(competencia, Taxa.ZERO));
    }

    /** Básica × fração, com o arredondamento do {@link Dinheiro}. */
    Dinheiro contrapartida(Dinheiro basica) {
        return new Dinheiro(basica.valor().multiply(percentualDaBasica.fracao()));
    }
}
