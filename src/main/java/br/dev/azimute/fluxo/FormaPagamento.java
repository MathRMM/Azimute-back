package br.dev.azimute.fluxo;

import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.folha.HoleriteId;
import br.dev.azimute.fluxo.tabelas.FormaDeGasto;

import java.util.Objects;

/**
 * Como o gasto foi pago (RF14). {@code sealed} de propósito (RNF02): a fase 5 do roadmap acrescenta o crédito, e
 * todo {@code switch} sem {@code default} deixa de compilar até tratar o caso novo.
 */
sealed interface FormaPagamento {

    /** Saiu de uma conta: vira uma {@code SAIDA} em {@code contas}. */
    record AVista(ContaId conta) implements FormaPagamento {
        public AVista {
            Objects.requireNonNull(conta, "conta");
        }
    }

    /** Descontado no holerite: o dinheiro nem chegou à conta, então não há movimentação. */
    record DescontoEmFolha(HoleriteId holerite) implements FormaPagamento {
        public DescontoEmFolha {
            Objects.requireNonNull(holerite, "holerite");
        }
    }

    /** A forma, gravada pelo nome em {@code fluxo.gastos.forma}. */
    default FormaDeGasto codigo() {
        return switch (this) {
            case AVista a -> FormaDeGasto.A_VISTA;
            case DescontoEmFolha d -> FormaDeGasto.DESCONTO_EM_FOLHA;
        };
    }
}
