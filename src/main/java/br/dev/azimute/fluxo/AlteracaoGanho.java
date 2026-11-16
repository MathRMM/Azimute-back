package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.tabelas.TipoGanho;

import java.time.LocalDate;

/** Campos editáveis do ganho avulso (RF22). Nulo quer dizer "não muda". */
record AlteracaoGanho(Competencia competencia, String descricao, TipoGanho tipo, Dinheiro bruto, Dinheiro liquido,
                      ContaId conta, LocalDate recebidoEm) {

    /** Líquido, conta ou data: o que a entrada em {@code contas} precisa acompanhar. */
    boolean mexeNaConta() {
        return liquido != null || conta != null || recebidoEm != null;
    }
}
