package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;

import java.time.LocalDate;

/** O que o cliente manda para registrar um gasto à vista (RF10). A conta é o atributo, não a modalidade (RN01). */
record NovoGasto(LocalDate data, String descricao, Dinheiro valor, CategoriaId categoria, ContaId conta) {}
