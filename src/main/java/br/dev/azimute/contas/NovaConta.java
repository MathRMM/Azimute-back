package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;

/** Dados de criação (RF01). {@code dataSaldoInicial} nula vale hoje; {@code limiteChequeEspecial} nulo vale zero. */
record NovaConta(String apelido, String instituicao, TipoConta tipo, Funcao funcao, Dinheiro saldoInicial,
                 LocalDate dataSaldoInicial, Dinheiro limiteChequeEspecial) {}
