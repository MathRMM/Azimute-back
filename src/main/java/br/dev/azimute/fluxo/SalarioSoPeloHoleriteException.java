package br.dev.azimute.fluxo;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** RF21, P4: o ganho {@code SALARIO} nasce só da importação do holerite, nunca da API avulsa. */
final class SalarioSoPeloHoleriteException extends ErroDeNegocio {

    SalarioSoPeloHoleriteException() {
        super("SALARIO_SO_PELO_HOLERITE", "Salário entra pelo holerite; aqui, use PLR, FREELA, REEMBOLSO ou OUTRO",
                Tipo.VALIDACAO, Map.of("tipo", TipoGanho.SALARIO.name()));
    }
}
