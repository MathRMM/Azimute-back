package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Identificador;

import java.util.Objects;
import java.util.UUID;

/** Identificador de gasto, vindo do cliente no {@code PUT} (idempotência). Público: o evento {@code GastoLancado} o leva. */
public record GastoId(UUID valor) implements Identificador {
    public GastoId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
