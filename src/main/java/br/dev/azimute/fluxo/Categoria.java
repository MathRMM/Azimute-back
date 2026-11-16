package br.dev.azimute.fluxo;

import java.util.Objects;

/** Categoria de gasto. Não é excluída, é desativada: gastos antigos continuam apontando para ela (RF04). */
record Categoria(CategoriaId id, String nome, boolean predefinida, boolean ativa) {

    Categoria {
        Objects.requireNonNull(id, "id");
        Modelo.exigirTexto(nome, "nome");
    }
}
