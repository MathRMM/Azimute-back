package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Cadastro de contas e saldo atual (RF01, RF03–RF05). Saldo negativo nunca bloqueia nada (RN11).
 * Valores e saldos não vão para o log (SEC02).
 */
@Service
class ContaService {

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    private final ContaRepository contas;
    private final ConciliacaoRepository conciliacoes;
    private final MovimentacaoRepository movimentacoes;
    private final Clock relogio;

    ContaService(ContaRepository contas, ConciliacaoRepository conciliacoes, MovimentacaoRepository movimentacoes,
                 Clock relogio) {
        this.contas = contas;
        this.conciliacoes = conciliacoes;
        this.movimentacoes = movimentacoes;
        this.relogio = relogio;
    }

    /**
     * Cria a conta e, na mesma transação, a primeira conciliação: o saldo inicial é o marco zero, com
     * divergência zero (RF01). Sem a conciliação, a conta não teria base de saldo.
     */
    @Transactional
    ContaComSaldo criar(NovaConta nova) {
        var limite = nova.limiteChequeEspecial() == null ? Dinheiro.ZERO : nova.limiteChequeEspecial();
        exigirLimiteSoEmCorrente(nova.tipo(), limite);
        var data = nova.dataSaldoInicial() == null ? hoje() : nova.dataSaldoInicial();

        var id = contas.inserir(nova.apelido().strip(), nova.instituicao().strip(), nova.tipo(), nova.funcao(), limite);
        conciliacoes.inserir(id, data, nova.saldoInicial(), nova.saldoInicial(), Dinheiro.ZERO, null);
        return comSaldo(buscar(id));
    }

    List<ContaComSaldo> listar(boolean incluirInativas) {
        return contas.listar(incluirInativas).stream().map(this::comSaldo).toList();
    }

    /** Só os campos informados mudam; desativar com saldo é permitido (P5). */
    @Transactional
    ContaComSaldo alterar(ContaId id, AlteracaoConta alteracao) {
        var atual = buscar(id);
        var apelido = alteracao.apelido() == null ? atual.apelido() : alteracao.apelido().strip();
        if (apelido.isEmpty()) {
            throw new DadosInvalidosException(Map.of("apelido", "não pode ficar em branco"));
        }
        var instituicao = alteracao.instituicao() == null ? atual.instituicao() : alteracao.instituicao().strip();
        var limite = alteracao.limiteChequeEspecial() == null
                ? atual.limiteChequeEspecial() : alteracao.limiteChequeEspecial();
        exigirLimiteSoEmCorrente(atual.tipo(), limite);

        var alterada = new Conta(id, apelido, instituicao, atual.tipo(),
                alteracao.funcao() == null ? atual.funcao() : alteracao.funcao(), limite,
                alteracao.ativa() == null ? atual.ativa() : alteracao.ativa());
        contas.atualizar(alterada);
        return comSaldo(alterada);
    }

    Saldo saldo(ContaId id) {
        return saldoDe(buscar(id));
    }

    private ContaComSaldo comSaldo(Conta conta) {
        return new ContaComSaldo(conta, saldoDe(conta));
    }

    /** Última conciliação + movimentações com data posterior a ela (RN06, P2). */
    private Saldo saldoDe(Conta conta) {
        var base = conciliacoes.ultima(conta.id())
                .orElseThrow(() -> new IllegalStateException("Conta sem conciliação inicial: " + conta.id()));
        var resumo = movimentacoes.resumoDesde(conta.id(), base.data());
        return CalculoDeSaldo.calcular(conta, base, resumo.efeito(), resumo.quantidade());
    }

    private Conta buscar(ContaId id) {
        return contas.buscar(id).orElseThrow(() -> new ContaNaoEncontradaException(id));
    }

    private static void exigirLimiteSoEmCorrente(TipoConta tipo, Dinheiro limite) {
        if (tipo != TipoConta.CORRENTE && limite.positivo()) {
            throw new LimiteSoEmContaCorrenteException(tipo);
        }
    }

    private LocalDate hoje() {
        return LocalDate.now(relogio.withZone(FUSO));
    }
}
