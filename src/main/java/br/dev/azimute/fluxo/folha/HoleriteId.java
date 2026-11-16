package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Identificador;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador de holerite. Público em Java porque o pacote base do {@code fluxo} o usa (gasto em folha, ganho de
 * salário); para o Spring Modulith, o subpacote {@code folha} continua interno ao módulo (T1).
 */
public record HoleriteId(UUID valor) implements Identificador {
    public HoleriteId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
