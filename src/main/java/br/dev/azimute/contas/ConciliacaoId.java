package br.dev.azimute.contas;

import java.util.Objects;
import java.util.UUID;

record ConciliacaoId(UUID valor) {
    ConciliacaoId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
