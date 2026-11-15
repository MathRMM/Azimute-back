package br.dev.azimute.fluxo.folha;

import br.dev.azimute.contas.ContaId;

import java.util.Objects;

/** Para onde vão o salário, o FGTS e a previdência (RF30). Uma configuração só. */
record ConfiguracaoFolha(ContaId salario, ContaId fgts, ContaId previdencia) {

    ConfiguracaoFolha {
        Objects.requireNonNull(salario, "salario");
        Objects.requireNonNull(fgts, "fgts");
        Objects.requireNonNull(previdencia, "previdencia");
    }
}
