package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Extrato do ponto de vista da conta (RF12): cada linha diz se foi crédito ou débito nela e qual a outra conta
 * da transferência ({@code contraparte}, nula nos outros tipos).
 */
record ExtratoResponse(UUID conta, LocalDate de, LocalDate ate, Dinheiro saldoInicial, Dinheiro saldoFinal,
                       List<Linha> linhas, List<Marco> conciliacoes) {

    record Linha(UUID id, String tipo, LocalDate data, Dinheiro valor, Movimentacao.Sentido sentido,
                 UUID contraparte, String motivo) {}

    record Marco(LocalDate data, Dinheiro saldoReal, Dinheiro saldoCalculado, Dinheiro divergencia,
                 String justificativa) {}

    static ExtratoResponse de(Extrato extrato) {
        var conta = extrato.conta();
        var linhas = extrato.linhas().stream().map(m -> linha(conta, m)).toList();
        var marcos = extrato.conciliacoes().stream()
                .map(c -> new Marco(c.data(), c.saldoReal(), c.saldoCalculado(), c.divergencia(), c.justificativa()))
                .toList();
        return new ExtratoResponse(conta.valor(), extrato.de(), extrato.ate(), extrato.saldoInicial(),
                extrato.saldoFinal(), linhas, marcos);
    }

    private static Linha linha(ContaId conta, Movimentacao m) {
        UUID contraparte = null;
        String motivo = null;
        if (m instanceof Movimentacao.Transferencia t) {
            contraparte = (t.origem().equals(conta) ? t.destino() : t.origem()).valor();
            motivo = t.motivo();
        }
        return new Linha(m.id().valor(), m.tipo(), m.data(), m.valor(), m.sentidoPara(conta), contraparte, motivo);
    }
}
