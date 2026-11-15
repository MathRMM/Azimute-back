package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Dinheiro;

import java.util.Objects;

/** Validações repetidas pelos registros do modelo. A API valida antes; aqui é a última barreira antes do banco. */
final class Modelo {

    private Modelo() {
    }

    static void exigirTexto(String texto, String campo) {
        Objects.requireNonNull(texto, campo);
        if (texto.isBlank()) {
            throw new IllegalArgumentException(campo + " em branco");
        }
    }

    /** RN03: valor de gasto e de recebimento é sempre positivo. */
    static void exigirPositivo(Dinheiro valor, String campo) {
        Objects.requireNonNull(valor, campo);
        if (!valor.positivo()) {
            throw new IllegalArgumentException(campo + " precisa ser positivo: " + valor);
        }
    }

    static void exigirNaoNegativo(Dinheiro valor, String campo) {
        Objects.requireNonNull(valor, campo);
        if (valor.negativo()) {
            throw new IllegalArgumentException(campo + " não pode ser negativo: " + valor);
        }
    }
}
