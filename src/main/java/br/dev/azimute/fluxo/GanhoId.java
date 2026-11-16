package br.dev.azimute.fluxo;

import java.util.Objects;
import java.util.UUID;

/** Identificador de ganho. Público: o evento {@code GanhoRegistrado} o leva. */
public record GanhoId(UUID valor) {
    public GanhoId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
