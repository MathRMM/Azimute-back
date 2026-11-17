package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Identificador;

import java.util.Objects;
import java.util.UUID;

/** Identificador de ganho. Público: o evento {@code GanhoRegistrado} o leva. */
public record GanhoId(UUID valor) implements Identificador {
    public GanhoId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
