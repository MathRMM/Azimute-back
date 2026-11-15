package br.dev.azimute.contas;

import java.util.Objects;
import java.util.UUID;

/** Identificador de conta. Público: os outros módulos guardam a conta de origem ou de destino por ele (RF31). */
public record ContaId(UUID valor) {
    public ContaId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
