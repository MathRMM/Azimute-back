package br.dev.azimute.fluxo;

/** O gasto com o nome atual da categoria: renomear a categoria muda o nome nos gastos antigos (RF04, RF13). */
record GastoComCategoria(Gasto gasto, String categoriaNome) {}
