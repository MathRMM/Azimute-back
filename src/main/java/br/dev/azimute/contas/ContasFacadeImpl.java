package br.dev.azimute.contas;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
class ContasFacadeImpl implements ContasFacade {

    private final ContaService contas;
    private final ContaRepository repositorio;
    private final MovimentacaoService movimentacoes;

    ContasFacadeImpl(ContaService contas, ContaRepository repositorio, MovimentacaoService movimentacoes) {
        this.contas = contas;
        this.repositorio = repositorio;
        this.movimentacoes = movimentacoes;
    }

    @Override
    public Dinheiro saldoAtual(ContaId conta) {
        return contas.saldo(conta).atual();
    }

    @Override
    public void registrarEntrada(ContaId destino, UUID ganhoId, Dinheiro valor, LocalDate data) {
        movimentacoes.registrarDeOutroModulo(new Movimentacao.Entrada(novoId(), destino, ganhoId, valor, data));
    }

    @Override
    public void registrarSaida(ContaId origem, UUID gastoId, Dinheiro valor, LocalDate data) {
        movimentacoes.registrarDeOutroModulo(new Movimentacao.Saida(novoId(), origem, gastoId, valor, data));
    }

    @Override
    public void registrarPagamentoFatura(ContaId origem, UUID cartaoId, Competencia fatura, Dinheiro valor,
                                         LocalDate data) {
        movimentacoes.registrarDeOutroModulo(
                new Movimentacao.PagamentoFatura(novoId(), origem, cartaoId, fatura, valor, data));
    }

    @Override
    public List<Conta> contasDeReserva() {
        return repositorio.listar(false).stream().filter(c -> c.funcao() == Funcao.RESERVA).toList();
    }

    @Override
    public Optional<Conta> buscarConta(ContaId id) {
        return repositorio.buscar(id);
    }

    @Override
    public void alterarLancamento(UUID referencia, ContaId conta, Dinheiro valor, LocalDate data) {
        movimentacoes.alterarDeOutroModulo(referencia, conta, valor, data);
    }

    @Override
    public void excluirLancamento(UUID referencia) {
        movimentacoes.excluirDeOutroModulo(referencia);
    }

    @Override
    public void restaurarLancamento(UUID referencia) {
        movimentacoes.restaurarDeOutroModulo(referencia);
    }

    private static MovimentacaoId novoId() {
        return new MovimentacaoId(UUID.randomUUID());
    }
}
