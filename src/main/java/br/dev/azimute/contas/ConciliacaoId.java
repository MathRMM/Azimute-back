package br.dev.azimute.contas;

import br.dev.azimute.comum.Identificador;

import java.util.Objects;
import java.util.UUID;

record ConciliacaoId(UUID valor) implements Identificador {
    ConciliacaoId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
