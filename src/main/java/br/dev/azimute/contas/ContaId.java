package br.dev.azimute.contas;

import br.dev.azimute.comum.Identificador;

import java.util.Objects;
import java.util.UUID;

/** Identificador de conta. Público: os outros módulos guardam a conta de origem ou de destino por ele (RF31). */
public record ContaId(UUID valor) implements Identificador {
    public ContaId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
