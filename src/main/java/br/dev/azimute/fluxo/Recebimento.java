package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Parte de um ganho que caiu numa conta. O {@code id} é a referência da {@code ENTRADA} em {@code contas} (T3).
 * O ganho é nulo enquanto um adiantamento lançado antes do holerite está solto (RF34).
 */
record Recebimento(UUID id, GanhoId ganho, Competencia competencia, Papel papel, Dinheiro valor, LocalDate data,
                   ContaId conta, boolean lancadoAntes) {

    enum Papel { AVULSO, ADIANTAMENTO, LIQUIDO }

    Recebimento {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(competencia, "competencia");
        Objects.requireNonNull(papel, "papel");
        Modelo.exigirPositivo(valor, "valor");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(conta, "conta");
    }
}
