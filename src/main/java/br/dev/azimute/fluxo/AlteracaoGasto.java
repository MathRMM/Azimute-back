package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;

import java.time.LocalDate;

/** Campos editáveis do gasto (RF11). Nulo quer dizer "não muda". */
record AlteracaoGasto(LocalDate data, String descricao, Dinheiro valor, CategoriaId categoria, ContaId conta) {

    /** Data, valor ou conta: o que a saída em {@code contas} precisa acompanhar. */
    boolean mexeNaConta() {
        return data != null || valor != null || conta != null;
    }
}
