package br.dev.azimute.contas;

import br.dev.azimute.comum.Identificador;

import java.util.Objects;
import java.util.UUID;

/** Na API, gerado no cliente (UUIDv7) para o {@code PUT /{id}} ser idempotente (P1); na fachada, pelo banco. */
record MovimentacaoId(UUID valor) implements Identificador {
    MovimentacaoId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
