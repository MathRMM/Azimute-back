package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Identificador;

import java.util.Objects;
import java.util.UUID;

/** Identificador de categoria de gasto. Público: o resumo do mês e o evento {@code GastoLancado} o levam. */
public record CategoriaId(UUID valor) implements Identificador {
    public CategoriaId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
