package br.dev.azimute.auth;

import java.util.Objects;
import java.util.UUID;

public record DispositivoId(UUID valor) {
    public DispositivoId {
        Objects.requireNonNull(valor, "valor");
    }
}
