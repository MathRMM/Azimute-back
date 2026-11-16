package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;

import java.util.List;
import java.util.Objects;

/**
 * O holerite como veio, sem interpretação. É a saída do leitor de PDF e a entrada do lançamento manual: todo o
 * processamento parte daqui (SPEC do fluxo, §6.3). A conferência dos totais (RN10) é feita depois, com erro de
 * domínio, e não neste construtor.
 */
record HoleriteLido(Competencia competencia, List<RubricaLida> rubricas, Dinheiro totalVencimentos,
                    Dinheiro totalDescontos, Dinheiro liquido, BasesLidas bases) {

    HoleriteLido {
        Objects.requireNonNull(competencia, "competencia");
        rubricas = List.copyOf(rubricas);
        Objects.requireNonNull(totalVencimentos, "totalVencimentos");
        Objects.requireNonNull(totalDescontos, "totalDescontos");
        Objects.requireNonNull(liquido, "liquido");
        Objects.requireNonNull(bases, "bases");
    }
}
