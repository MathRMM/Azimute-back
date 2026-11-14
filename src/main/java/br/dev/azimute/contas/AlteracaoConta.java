package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;

/** Campos editáveis (RF04). Nulo quer dizer "não muda". {@code tipo} não é editável. */
record AlteracaoConta(String apelido, String instituicao, Funcao funcao, Dinheiro limiteChequeEspecial,
                      Boolean ativa) {}
