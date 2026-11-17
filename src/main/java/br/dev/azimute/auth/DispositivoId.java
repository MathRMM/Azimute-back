package br.dev.azimute.auth;

import br.dev.azimute.comum.Identificador;

import java.util.Objects;
import java.util.UUID;

public record DispositivoId(UUID valor) implements Identificador {
    public DispositivoId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
