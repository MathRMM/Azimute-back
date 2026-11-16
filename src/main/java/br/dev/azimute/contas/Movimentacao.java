package br.dev.azimute.contas;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Toda mudança de saldo é uma movimentação (RF14). Os cinco tipos existem para impedir contagem dupla:
 * transferência e pagamento de fatura <b>não são gasto</b> (RN02, RN03).
 * <p>
 * {@code sealed} de propósito (RNF02): todo {@code switch} sobre movimentações trata os cinco casos, sem
 * {@code default}. Um tipo novo quebra a compilação em vez de ser somado errado em silêncio.
 * <p>
 * O valor é sempre positivo; o sentido vem do tipo, não do sinal (RN05).
 */
sealed interface Movimentacao {

    enum Sentido { CREDITO, DEBITO, NAO_ENVOLVE }

    MovimentacaoId id();

    Dinheiro valor();

    LocalDate data();

    /** Ganho creditado numa conta. O ganho pertence ao {@code fluxo} (T1). */
    record Entrada(MovimentacaoId id, ContaId destino, UUID ganhoId, Dinheiro valor, LocalDate data)
            implements Movimentacao {
        public Entrada {
            validar(id, valor, data);
            Objects.requireNonNull(destino, "destino");
        }
    }

    /** Gasto à vista debitado de uma conta. O gasto pertence ao {@code fluxo} (T1). */
    record Saida(MovimentacaoId id, ContaId origem, UUID gastoId, Dinheiro valor, LocalDate data)
            implements Movimentacao {
        public Saida {
            validar(id, valor, data);
            Objects.requireNonNull(origem, "origem");
        }
    }

    /** Dinheiro mudando de lugar. Não é gasto (RN02). */
    record Transferencia(MovimentacaoId id, ContaId origem, ContaId destino, Dinheiro valor, LocalDate data,
                         String motivo) implements Movimentacao {
        public Transferencia {
            validar(id, valor, data);
            Objects.requireNonNull(origem, "origem");
            Objects.requireNonNull(destino, "destino");
            if (origem.equals(destino)) {
                throw new IllegalArgumentException("Transferência precisa de contas diferentes");
            }
        }
    }

    /** Liquidação de fatura de cartão. Não é gasto: a compra já foi lançada (RN03). */
    record PagamentoFatura(MovimentacaoId id, ContaId origem, UUID cartaoId, Competencia fatura, Dinheiro valor,
                           LocalDate data) implements Movimentacao {
        public PagamentoFatura {
            validar(id, valor, data);
            Objects.requireNonNull(origem, "origem");
            Objects.requireNonNull(fatura, "fatura");
        }
    }

    /** Rendimento de aplicação. Credita a conta; não é ganho de trabalho. */
    record Rendimento(MovimentacaoId id, ContaId destino, Dinheiro valor, LocalDate data) implements Movimentacao {
        public Rendimento {
            validar(id, valor, data);
            Objects.requireNonNull(destino, "destino");
        }
    }

    /** O tipo, gravado pelo nome em {@code contas.movimentacoes.tipo}. */
    default TipoMovimentacao tipo() {
        return switch (this) {
            case Entrada e -> TipoMovimentacao.ENTRADA;
            case Saida s -> TipoMovimentacao.SAIDA;
            case Transferencia t -> TipoMovimentacao.TRANSFERENCIA;
            case PagamentoFatura p -> TipoMovimentacao.PAGAMENTO_FATURA;
            case Rendimento r -> TipoMovimentacao.RENDIMENTO;
        };
    }

    /** Crédito, débito ou nada, do ponto de vista de {@code conta}. Transferência é as duas coisas, uma em cada ponta. */
    default Sentido sentidoPara(ContaId conta) {
        return switch (this) {
            case Entrada e -> e.destino().equals(conta) ? Sentido.CREDITO : Sentido.NAO_ENVOLVE;
            case Rendimento r -> r.destino().equals(conta) ? Sentido.CREDITO : Sentido.NAO_ENVOLVE;
            case Saida s -> s.origem().equals(conta) ? Sentido.DEBITO : Sentido.NAO_ENVOLVE;
            case PagamentoFatura p -> p.origem().equals(conta) ? Sentido.DEBITO : Sentido.NAO_ENVOLVE;
            case Transferencia t -> t.origem().equals(conta) ? Sentido.DEBITO
                    : t.destino().equals(conta) ? Sentido.CREDITO : Sentido.NAO_ENVOLVE;
        };
    }

    /** O valor com sinal para o saldo de {@code conta}: positivo no crédito, negativo no débito, zero se não envolve. */
    default Dinheiro efeitoNoSaldo(ContaId conta) {
        return switch (sentidoPara(conta)) {
            case CREDITO -> valor();
            case DEBITO -> Dinheiro.ZERO.menos(valor());
            case NAO_ENVOLVE -> Dinheiro.ZERO;
        };
    }

    private static void validar(MovimentacaoId id, Dinheiro valor, LocalDate data) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(valor, "valor");
        Objects.requireNonNull(data, "data");
        if (!valor.positivo()) {
            throw new IllegalArgumentException("Valor da movimentação precisa ser positivo: " + valor);
        }
    }
}
