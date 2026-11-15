package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;

/**
 * Publicado quando um ganho é registrado (RF52). Sai pelo {@code ApplicationEventPublisher} dentro da transação; o
 * Spring Modulith entrega aos outros módulos depois do commit (T13). Reenvio idempotente não publica de novo.
 */
public record GanhoRegistrado(GanhoId ganhoId, Competencia competencia, Dinheiro liquido) {}
