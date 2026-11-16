package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Dinheiro;

import java.util.Objects;
import java.util.function.Function;

/**
 * RN10: antes de lançar qualquer coisa, a soma das rubricas de vencimento é o total de vencimentos, a de descontos é
 * o total de descontos, e vencimentos menos descontos é o líquido. O erro aponta qual conferência falhou.
 */
final class ConferenciaDeHolerite {

    private ConferenciaDeHolerite() {
    }

    static void conferir(HoleriteLido holerite) {
        exigir("vencimentos", holerite.totalVencimentos(), somar(holerite, RubricaLida::vencimento));
        exigir("descontos", holerite.totalDescontos(), somar(holerite, RubricaLida::desconto));
        exigir("liquido", holerite.liquido(), holerite.totalVencimentos().menos(holerite.totalDescontos()));
    }

    private static Dinheiro somar(HoleriteLido holerite, Function<RubricaLida, Dinheiro> coluna) {
        return holerite.rubricas().stream().map(coluna).filter(Objects::nonNull)
                .reduce(Dinheiro.ZERO, Dinheiro::mais);
    }

    private static void exigir(String conferencia, Dinheiro informado, Dinheiro calculado) {
        if (!informado.equals(calculado)) {
            throw new HoleriteInconsistenteException(conferencia, informado, calculado);
        }
    }
}
