package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * A visão central do módulo (RF06, RN12, RN13).
 * <ul>
 *   <li>{@code posicaoGeral}: todas as contas ativas, de todos os tipos, menos as dívidas de crédito. É a
 *       variável global do dinheiro e pode ser negativa.</li>
 *   <li>{@code saldoEmConta}: só os saldos positivos das contas correntes ativas (P7). A corrente negativa
 *       entra como zero aqui: o negativo é cheque especial e já está na posição geral (RN11).</li>
 * </ul>
 * Nunca soma categoria de reserva (RN01).
 */
@Service
class ConsolidadoService {

    record Consolidado(Dinheiro posicaoGeral, Dinheiro dividas, Dinheiro saldoEmConta, List<ContaComSaldo> contas) {}

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
        var totalContas = ativas.stream().map(c -> c.saldo().atual()).reduce(Dinheiro.ZERO, Dinheiro::mais);
        var saldoEmConta = ativas.stream()
                .filter(c -> c.conta().tipo() == TipoConta.CORRENTE && c.saldo().atual().positivo())
                .map(c -> c.saldo().atual())
                .reduce(Dinheiro.ZERO, Dinheiro::mais);
        return new Consolidado(totalContas.menos(dividas), dividas, saldoEmConta, ativas);
    }
}
