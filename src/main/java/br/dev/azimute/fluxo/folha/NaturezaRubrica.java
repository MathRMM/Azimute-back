package br.dev.azimute.fluxo.folha;

/** O que uma rubrica do holerite significa (RF35). Só {@code GASTO} tem categoria. */
enum NaturezaRubrica {
    PROVENTO, INSS, IRRF, PREVIDENCIA_BASICA, PREVIDENCIA_VOLUNTARIA, ADIANTAMENTO, GASTO, OUTRO_DESCONTO
}
