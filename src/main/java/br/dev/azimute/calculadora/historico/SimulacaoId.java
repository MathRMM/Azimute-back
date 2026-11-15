package br.dev.azimute.calculadora.historico;

import java.util.Objects;
import java.util.UUID;

/** Identificador gerado no cliente (UUIDv7): é o que torna o {@code PUT /{id}} idempotente. */
public record SimulacaoId(UUID valor) {
    public SimulacaoId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
