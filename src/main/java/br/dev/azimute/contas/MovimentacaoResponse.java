package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;
import java.util.UUID;

/** Movimentação como a API devolve. Campos que não se aplicam ao tipo ficam nulos. */
record MovimentacaoResponse(UUID id, String tipo, UUID origem, UUID destino, Dinheiro valor, LocalDate data,
                            String motivo) {

    static MovimentacaoResponse de(Movimentacao m) {
        return switch (m) {
            case Movimentacao.Transferencia t -> new MovimentacaoResponse(t.id().valor(), t.tipo(),
                    t.origem().valor(), t.destino().valor(), t.valor(), t.data(), t.motivo());
            case Movimentacao.Rendimento r -> new MovimentacaoResponse(r.id().valor(), r.tipo(), null,
                    r.destino().valor(), r.valor(), r.data(), null);
            case Movimentacao.Entrada e -> new MovimentacaoResponse(e.id().valor(), e.tipo(), null,
                    e.destino().valor(), e.valor(), e.data(), null);
            case Movimentacao.Saida s -> new MovimentacaoResponse(s.id().valor(), s.tipo(), s.origem().valor(),
                    null, s.valor(), s.data(), null);
            case Movimentacao.PagamentoFatura p -> new MovimentacaoResponse(p.id().valor(), p.tipo(),
                    p.origem().valor(), null, p.valor(), p.data(), null);
        };
    }
}
