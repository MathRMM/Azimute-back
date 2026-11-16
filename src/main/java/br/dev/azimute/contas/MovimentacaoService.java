package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transferências e rendimentos pela API, exclusão lógica, restauração e extrato (RF10–RF15).
 * Saldo negativo nunca bloqueia (RN11); data futura é aceita e já conta no saldo (RN15).
 */
@Service
class MovimentacaoService {

    private final MovimentacaoRepository movimentacoes;
    private final ContaRepository contas;
    private final ConciliacaoRepository conciliacoes;
    private final ContaService saldos;

    MovimentacaoService(MovimentacaoRepository movimentacoes, ContaRepository contas,
                        ConciliacaoRepository conciliacoes, ContaService saldos) {
        this.movimentacoes = movimentacoes;
        this.contas = contas;
        this.conciliacoes = conciliacoes;
        this.saldos = saldos;
    }

    /**
     * Grava com o ID do cliente, idempotente (P1): mesmo conteúdo devolve a existente; conteúdo diferente é
     * conflito; ID excluído não volta por reenvio (T9).
     * <p>
     * O ID existente é conferido antes das contas: um reenvio continua respondendo 200 mesmo que a conta tenha
     * sido desativada depois do primeiro envio.
     */
    Movimentacao registrar(MovimentacaoId id, NovaMovimentacao nova) {
        var existente = movimentacoes.buscar(id);
        if (existente.isPresent()) {
            return conferirReenvio(existente.get(), montar(id, nova));
        }

        var movimentacao = montar(id, nova);
        exigirContasAtivas(movimentacao);
        if (movimentacoes.inserirSeAusente(movimentacao)) {
            return movimentacao;
        }
        // Outro envio do mesmo ID gravou entre a busca e a inserção.
        return conferirReenvio(movimentacoes.buscar(id).orElseThrow(), movimentacao);
    }

    /**
     * Entrada, saída ou pagamento de fatura vindos da fachada (RF30). Repetir a mesma referência (ganho, gasto ou
     * cartão + fatura) não lança de novo: o índice único parcial barra e a inserção vira nada (T5).
     */
    void registrarDeOutroModulo(Movimentacao movimentacao) {
        exigirContasAtivas(movimentacao);
        movimentacoes.inserirSeAusente(movimentacao);
    }

    /**
     * Correção de um ganho ou gasto pelo módulo dono (RF42 do PRD do fluxo): a entrada ou saída viva muda de conta,
     * valor e data no lugar. A conta nova precisa existir e estar ativa (RN08).
     */
    void alterarDeOutroModulo(UUID referencia, ContaId conta, Dinheiro valor, LocalDate data) {
        var registro = movimentacoes.buscarPorReferencia(referencia)
                .filter(r -> !r.excluida())
                .orElseThrow(() -> new LancamentoNaoEncontradoException(referencia));
        var alterada = switch (registro.movimentacao()) {
            case Movimentacao.Entrada e -> new Movimentacao.Entrada(e.id(), conta, e.ganhoId(), valor, data);
            case Movimentacao.Saida s -> new Movimentacao.Saida(s.id(), conta, s.gastoId(), valor, data);
            // A busca por referência só traz entrada e saída; os outros tipos não têm referência de ganho ou gasto.
            case Movimentacao.Transferencia t -> throw new LancamentoNaoEncontradoException(referencia);
            case Movimentacao.PagamentoFatura p -> throw new LancamentoNaoEncontradoException(referencia);
            case Movimentacao.Rendimento r -> throw new LancamentoNaoEncontradoException(referencia);
        };
        exigirContasAtivas(alterada);
        movimentacoes.alterar(alterada);
    }

    /** Exclusão pelo módulo dono. Referência inexistente ou já excluída não é erro (T3). */
    void excluirDeOutroModulo(UUID referencia) {
        movimentacoes.buscarPorReferencia(referencia)
                .filter(r -> !r.excluida())
                .ifPresent(r -> movimentacoes.excluir(r.movimentacao().id()));
    }

    /** Restauração pelo módulo dono. Se já há uma viva com a referência, nada muda (T3). */
    void restaurarDeOutroModulo(UUID referencia) {
        var registro = movimentacoes.buscarPorReferencia(referencia)
                .orElseThrow(() -> new LancamentoNaoEncontradoException(referencia));
        if (registro.excluida()) {
            movimentacoes.restaurar(registro.movimentacao().id());
        }
    }

    /** Exclusão lógica (RF13). Excluir o que não existe ou já foi excluído não é erro (T3). */
    void excluir(MovimentacaoId id) {
        var registro = movimentacoes.buscar(id);
        if (registro.isEmpty()) {
            return;
        }
        exigirDaApi(registro.get().movimentacao());
        if (!registro.get().excluida()) {
            movimentacoes.excluir(id);
        }
    }

    /** Desfaz a exclusão lógica (RF15). Restaurar o que está vivo não muda nada (T3). */
    Movimentacao restaurar(MovimentacaoId id) {
        var registro = movimentacoes.buscar(id).orElseThrow(() -> new MovimentacaoNaoEncontradaException(id));
        exigirDaApi(registro.movimentacao());
        if (registro.excluida()) {
            movimentacoes.restaurar(id);
        }
        return registro.movimentacao();
    }

    /** Extrato do período, com o saldo no início e no fim e as conciliações como marcos (RF12). */
    Extrato extrato(ContaId conta, LocalDate de, LocalDate ate) {
        if (de.isAfter(ate)) {
            throw new PeriodoInvalidoException(de, ate);
        }
        contas.buscar(conta).orElseThrow(() -> new ContaNaoEncontradaException(conta));
        return new Extrato(conta, de, ate, saldos.saldoEm(conta, de.minusDays(1)), saldos.saldoEm(conta, ate),
                movimentacoes.doPeriodo(conta, de, ate), conciliacoes.doPeriodo(conta, de, ate));
    }

    private static Movimentacao conferirReenvio(MovimentacaoRepository.Registro existente, Movimentacao nova) {
        if (existente.excluida()) {
            throw new MovimentacaoExcluidaException(nova.id());
        }
        if (!existente.movimentacao().equals(nova)) {
            throw new MovimentacaoDivergenteException(nova.id());
        }
        return existente.movimentacao();
    }

    /** Só transferência e rendimento entram pela API (RF14). */
    private static Movimentacao montar(MovimentacaoId id, NovaMovimentacao nova) {
        return switch (TipoMovimentacao.doTexto(nova.tipo()).orElse(null)) {
            case TRANSFERENCIA -> transferencia(id, nova);
            case RENDIMENTO -> rendimento(id, nova);
            case null, default -> throw new TipoNaoPermitidoException(nova.tipo());
        };
    }

    private static Movimentacao transferencia(MovimentacaoId id, NovaMovimentacao nova) {
        var erros = new LinkedHashMap<String, Object>();
        if (nova.origem() == null) {
            erros.put("origem", "obrigatória na transferência");
        }
        if (nova.destino() == null) {
            erros.put("destino", "obrigatório na transferência");
        }
        exigirSemErros(erros);
        if (nova.origem().equals(nova.destino())) {
            throw new TransferenciaParaAMesmaContaException(nova.origem());
        }
        return new Movimentacao.Transferencia(id, nova.origem(), nova.destino(), nova.valor(), nova.data(),
                motivo(nova.motivo()));
    }

    private static Movimentacao rendimento(MovimentacaoId id, NovaMovimentacao nova) {
        var erros = new LinkedHashMap<String, Object>();
        if (nova.destino() == null) {
            erros.put("destino", "obrigatório no rendimento");
        }
        if (nova.origem() != null) {
            erros.put("origem", "rendimento não tem conta de origem");
        }
        exigirSemErros(erros);
        return new Movimentacao.Rendimento(id, nova.destino(), nova.valor(), nova.data());
    }

    private void exigirContasAtivas(Movimentacao movimentacao) {
        switch (movimentacao) {
            case Movimentacao.Transferencia t -> {
                exigirAtiva(t.origem());
                exigirAtiva(t.destino());
            }
            case Movimentacao.Rendimento r -> exigirAtiva(r.destino());
            case Movimentacao.Entrada e -> exigirAtiva(e.destino());
            case Movimentacao.Saida s -> exigirAtiva(s.origem());
            case Movimentacao.PagamentoFatura p -> exigirAtiva(p.origem());
        }
    }

    private void exigirAtiva(ContaId id) {
        var conta = contas.buscar(id).orElseThrow(() -> new ContaNaoEncontradaException(id));
        if (!conta.ativa()) {
            throw new ContaInativaException(id);
        }
    }

    /** Entrada, saída e pagamento de fatura pertencem a {@code fluxo} e {@code cartoes} (T2). */
    private static void exigirDaApi(Movimentacao movimentacao) {
        switch (movimentacao) {
            case Movimentacao.Transferencia t -> { }
            case Movimentacao.Rendimento r -> { }
            case Movimentacao.Entrada e -> throw new MovimentacaoDeOutroModuloException(e.id());
            case Movimentacao.Saida s -> throw new MovimentacaoDeOutroModuloException(s.id());
            case Movimentacao.PagamentoFatura p -> throw new MovimentacaoDeOutroModuloException(p.id());
        }
    }

    private static void exigirSemErros(Map<String, Object> erros) {
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException(erros);
        }
    }

    private static String motivo(String motivo) {
        return motivo == null || motivo.isBlank() ? null : motivo.strip();
    }
}
