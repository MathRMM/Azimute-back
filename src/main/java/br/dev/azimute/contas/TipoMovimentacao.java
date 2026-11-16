package br.dev.azimute.contas;

import java.util.Arrays;
import java.util.Optional;

/** Os cinco tipos de movimentação (RF14), com o nome gravado em {@code contas.movimentacoes.tipo}. */
enum TipoMovimentacao {
    ENTRADA, SAIDA, TRANSFERENCIA, PAGAMENTO_FATURA, RENDIMENTO;

    /** O texto que a API recebeu; vazio quando não é nenhum dos cinco, para o serviço responder com erro claro. */
    static Optional<TipoMovimentacao> doTexto(String texto) {
        return Arrays.stream(values()).filter(t -> t.name().equals(texto)).findFirst();
    }
}
