package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeNegocio;

/** SEC04: o arquivo não começa com a assinatura {@code %PDF-}. */
final class ArquivoNaoEPdfException extends ErroDeNegocio {

    ArquivoNaoEPdfException() {
        super("ARQUIVO_NAO_E_PDF", "O arquivo enviado não é um PDF", Tipo.VALIDACAO);
    }
}
