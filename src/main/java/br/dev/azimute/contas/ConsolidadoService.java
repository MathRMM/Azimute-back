package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

/**
 * A visão central do módulo (RF06, RN12, RN13).
 * <ul>
 *   <li>{@code posicaoGeral}: as contas ativas, menos as dívidas de crédito. É a variável global do dinheiro e
 *       pode ser negativa. FGTS e previdência ficam fora (RN16 do PRD do fluxo).</li>
 *   <li>{@code saldoEmConta}: só os saldos positivos das contas correntes ativas (P7). A corrente negativa
 *       entra como zero aqui: o negativo é cheque especial e já está na posição geral (RN11).</li>
 *   <li>{@code bloqueado}: a soma das contas ativas de FGTS e previdência (RF41 do PRD do fluxo). A lista de
 *       contas continua trazendo todas.</li>
 * </ul>
 * Nunca soma categoria de reserva (RN01).
 */
@Service
class ConsolidadoService {

    record Consolidado(Dinheiro posicaoGeral, Dinheiro dividas, Dinheiro saldoEmConta, Dinheiro bloqueado,
                       List<ContaComSaldo> contas) {}

    private final ContaService contas;
    private final List<FontesDeDivida> fontes;

    /** Sem o módulo {@code cartoes}, não existe nenhuma fonte de dívida e a lista fica vazia (RF32). */
    @Autowired
    ConsolidadoService(ContaService contas, ObjectProvider<FontesDeDivida> fontes) {
        this(contas, fontes.orderedStream().toList());
    }

    ConsolidadoService(ContaService contas, List<FontesDeDivida> fontes) {
        this.contas = contas;
        this.fontes = List.copyOf(fontes);
    }

    Consolidado consolidado() {
        var ativas = contas.listar(false);
        var dividas = fontes.stream().map(FontesDeDivida::totalDevido).reduce(Dinheiro.ZERO, Dinheiro::mais);
        var totalContas = soma(ativas.stream().filter(c -> !c.conta().tipo().bloqueado()));
        var bloqueado = soma(ativas.stream().filter(c -> c.conta().tipo().bloqueado()));
        var saldoEmConta = ativas.stream()
                .filter(c -> c.conta().tipo() == TipoConta.CORRENTE && c.saldo().atual().positivo())
                .map(c -> c.saldo().atual())
                .reduce(Dinheiro.ZERO, Dinheiro::mais);
        return new Consolidado(totalContas.menos(dividas), dividas, saldoEmConta, bloqueado, ativas);
    }

    private static Dinheiro soma(Stream<ContaComSaldo> contas) {
        return contas.map(c -> c.saldo().atual()).reduce(Dinheiro.ZERO, Dinheiro::mais);
    }
}
