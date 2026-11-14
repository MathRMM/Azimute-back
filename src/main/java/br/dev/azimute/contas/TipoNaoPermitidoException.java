package br.dev.azimute.contas;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Entrada, saída e pagamento de fatura só entram pela fachada, vindos de outro módulo (RF14). */
final class TipoNaoPermitidoException extends ErroDeNegocio {

    TipoNaoPermitidoException(String tipo) {
        super("TIPO_NAO_PERMITIDO", "Pela API só entram transferência e rendimento", Tipo.VALIDACAO, Map.of("tipo", tipo));
    }
}
