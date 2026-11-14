package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;

/**
 * O que a API recebe no {@code PUT /api/contas/movimentacoes/{id}} (RF10, RF11). O {@code tipo} chega como texto
 * para que tipos que existem mas não entram pela API recebam um erro claro (RF14). A data pode ser futura (RN15).
 */
record NovaMovimentacao(String tipo, ContaId origem, ContaId destino, Dinheiro valor, LocalDate data, String motivo) {}
