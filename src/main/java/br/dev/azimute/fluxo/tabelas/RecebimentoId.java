package br.dev.azimute.fluxo.tabelas;

import br.dev.azimute.comum.Identificador;

import java.util.Objects;
import java.util.UUID;

/** Identificador de recebimento. Público: ganhos e a folha gravam recebimentos pela {@link TabelaDeRecebimentos}. */
public record RecebimentoId(UUID valor) implements Identificador {
    public RecebimentoId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
