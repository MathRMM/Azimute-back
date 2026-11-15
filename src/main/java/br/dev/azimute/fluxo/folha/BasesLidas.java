package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Dinheiro;

/** O rodapé do holerite. Cada campo pode faltar, conforme o layout. */
record BasesLidas(Dinheiro salarioBase, Dinheiro baseInss, Dinheiro baseFgts, Dinheiro fgtsMes, Dinheiro baseIrrf) {
}
