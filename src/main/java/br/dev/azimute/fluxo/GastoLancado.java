package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;

/**
 * Publicado quando um gasto é registrado (RF52). Sai pelo {@code ApplicationEventPublisher} dentro da transação; o
 * Spring Modulith entrega aos outros módulos depois do commit (T13). Reenvio idempotente não publica de novo.
 */
public record GastoLancado(GastoId gastoId, LocalDate data, Dinheiro valor, CategoriaId categoriaId) {}
