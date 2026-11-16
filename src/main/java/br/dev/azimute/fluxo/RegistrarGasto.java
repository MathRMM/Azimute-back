package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Comando da fachada para um gasto à vista (RF51), com o ID gerado por quem chama: repetir o mesmo comando não
 * duplica, como no {@code PUT /api/gastos/{id}}.
 */
public record RegistrarGasto(GastoId id, LocalDate data, String descricao, Dinheiro valor, CategoriaId categoria,
                             ContaId conta) {

    public RegistrarGasto {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(descricao, "descricao");
        Objects.requireNonNull(valor, "valor");
        Objects.requireNonNull(categoria, "categoria");
        Objects.requireNonNull(conta, "conta");
    }
}
