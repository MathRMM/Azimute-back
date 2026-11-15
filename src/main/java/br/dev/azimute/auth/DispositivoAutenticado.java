package br.dev.azimute.auth;

/** O principal de toda requisição autenticada: o aparelho dono do token. */
public record DispositivoAutenticado(DispositivoId id, String nome) {
}
