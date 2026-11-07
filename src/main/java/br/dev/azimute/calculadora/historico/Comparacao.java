package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.Dinheiro;

import java.util.List;

/**
 * Propostas de um bem, da melhor (menor taxa) para a pior (decisão D4).
 *
 * @param economiaEntreMelhorEPior juros da pior − juros da melhor, em reais: o argumento da mesa de negociação
 */
record Comparacao(String bem, List<SimulacaoSalva> propostas, Dinheiro economiaEntreMelhorEPior) {

    SimulacaoSalva melhor() {
        return propostas.isEmpty() ? null : propostas.getFirst();
    }
}
