package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.DiasUteis;

import java.time.LocalDate;

/**
 * RN18: o salário é pago no próprio mês da competência, o adiantamento no dia 15 e o líquido no último dia, sempre
 * antecipando para o dia útil anterior. A data informada na importação prevalece.
 */
final class DatasDoSalario {

    private DatasDoSalario() {
    }

    static LocalDate adiantamento(Competencia competencia) {
        return DiasUteis.diaUtilAnteriorOuMesmo(competencia.mes().atDay(15));
    }

    static LocalDate liquido(Competencia competencia) {
        return DiasUteis.diaUtilAnteriorOuMesmo(competencia.mes().atEndOfMonth());
    }

    static LocalDate adiantamentoOu(LocalDate informada, Competencia competencia) {
        return informada != null ? informada : adiantamento(competencia);
    }

    static LocalDate liquidoOu(LocalDate informada, Competencia competencia) {
        return informada != null ? informada : liquido(competencia);
    }
}
