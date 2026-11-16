package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Holerite importado. A contrapartida da previdência é gravada na importação pela regra vigente e só muda por
 * correção explícita (RN19, T7). Impostos e totais do resumo saem das rubricas, na leitura (T10).
 */
record Holerite(HoleriteId id, Competencia competencia, Origem origem, String hash, Dinheiro totalVencimentos,
                Dinheiro totalDescontos, Dinheiro liquido, BasesLidas bases, Dinheiro contrapartidaPrevidencia,
                LocalDate dataAdiantamento, LocalDate dataLiquido) {

    enum Origem { PDF, MANUAL }

    Holerite {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(competencia, "competencia");
        Objects.requireNonNull(origem, "origem");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(bases, "bases");
        Objects.requireNonNull(contrapartidaPrevidencia, "contrapartidaPrevidencia");
        Objects.requireNonNull(dataLiquido, "dataLiquido");
        if (!totalVencimentos.menos(totalDescontos).equals(liquido)) {
            throw new IllegalArgumentException("Líquido diferente de vencimentos menos descontos");
        }
    }
}
