package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeNegocio;

/** RF30: sem as contas do salário, do FGTS e da previdência, a folha não lança nada. */
final class FolhaNaoConfiguradaException extends ErroDeNegocio {

    FolhaNaoConfiguradaException() {
        super("FOLHA_NAO_CONFIGURADA",
                "Configure antes as contas do salário, do FGTS e da previdência (PUT /api/folha/configuracao)",
                Tipo.VALIDACAO);
    }
}
