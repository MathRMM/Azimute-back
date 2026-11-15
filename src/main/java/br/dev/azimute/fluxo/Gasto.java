package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;
import java.util.Objects;

/** Dinheiro que saiu. A competência é a da {@code data} (RN09); o valor é sempre positivo (RN03). */
record Gasto(GastoId id, LocalDate data, String descricao, Dinheiro valor, CategoriaId categoria,
             FormaPagamento forma) {

    Gasto {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(data, "data");
        Modelo.exigirTexto(descricao, "descricao");
        Modelo.exigirPositivo(valor, "valor");
        Objects.requireNonNull(categoria, "categoria");
        Objects.requireNonNull(forma, "forma");
    }
}
