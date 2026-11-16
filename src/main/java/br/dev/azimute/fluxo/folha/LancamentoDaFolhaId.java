package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Identificador;

import java.util.Objects;
import java.util.UUID;

/** Identificador de um lançamento de FGTS ou previdência gerado pelo holerite ({@code fluxo.lancamentos_folha}). */
record LancamentoDaFolhaId(UUID valor) implements Identificador {
    LancamentoDaFolhaId {
        Objects.requireNonNull(valor, "valor");
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
