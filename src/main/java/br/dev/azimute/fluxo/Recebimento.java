package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.tabelas.PapelDeRecebimento;
import br.dev.azimute.fluxo.tabelas.RecebimentoId;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Parte de um ganho que caiu numa conta. O {@code id} é a referência da {@code ENTRADA} em {@code contas} (T3).
 * O ganho é nulo enquanto um adiantamento lançado antes do holerite está solto (RF34).
 */
record Recebimento(RecebimentoId id, GanhoId ganho, Competencia competencia, PapelDeRecebimento papel, Dinheiro valor, LocalDate data,
                   ContaId conta, boolean lancadoAntes) {

    Recebimento {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(competencia, "competencia");
        Objects.requireNonNull(papel, "papel");
        Modelo.exigirPositivo(valor, "valor");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(conta, "conta");
    }
}
