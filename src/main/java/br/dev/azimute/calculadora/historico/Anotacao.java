package br.dev.azimute.calculadora.historico;

/**
 * O que um PATCH pode alterar. {@code alteraObservacao} distingue "campo ausente" (não muda) de
 * "observação nula" (apaga). {@code status} nulo significa "não muda".
 */
record Anotacao(boolean alteraObservacao, String observacao, StatusSimulacao status) {
}
