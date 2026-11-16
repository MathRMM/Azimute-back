package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.tabelas.TipoGanho;

import java.time.LocalDate;

/**
 * Ganho avulso já recebido (RF20). A competência é informada e pode ser outra que a do recebimento (RN09); o líquido
 * cai inteiro na conta, num recebimento só (T5).
 */
record NovoGanho(Competencia competencia, String descricao, TipoGanho tipo, Dinheiro bruto, Dinheiro liquido,
                 ContaId conta, LocalDate recebidoEm) {}
