package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.contas.ContasFacade;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Gastos à vista (RF10–RF13) e a edição limitada do gasto em folha (RF15). Todo efeito em conta passa pela fachada
 * de {@code contas}, na mesma transação do gasto (RN04): a fachada vem antes da gravação, para que conta inexistente
 * ou inativa responda com o erro de {@code contas} (RN07) em vez de uma violação de chave estrangeira.
 */
@Service
class GastoService {

    /** O gasto e se este envio o criou (201) ou é um reenvio (200). */
    record Resultado(GastoComCategoria gasto, boolean criado) {}

    private final GastoRepository gastos;
    private final CategoriaService categorias;
    private final ContasFacade contas;
    private final ApplicationEventPublisher eventos;

    GastoService(GastoRepository gastos, CategoriaService categorias, ContasFacade contas,
                 ApplicationEventPublisher eventos) {
        this.gastos = gastos;
        this.categorias = categorias;
        this.contas = contas;
        this.eventos = eventos;
    }

    /**
     * Idempotente pelo ID do cliente: mesmo conteúdo devolve o existente; outro conteúdo ou ID excluído é conflito.
     * O ID existente é conferido antes da categoria e da conta, para o reenvio continuar respondendo 200 mesmo que
     * elas tenham sido desativadas depois do primeiro envio.
     */
    @Transactional
    Resultado registrar(GastoId id, NovoGasto novo) {
        var gasto = new Gasto(id, novo.data(), novo.descricao().strip(), novo.valor(), novo.categoria(),
                new FormaPagamento.AVista(novo.conta()));
        var existente = gastos.buscar(id);
        if (existente.isPresent()) {
            return reenvio(existente.get(), gasto);
        }

        var categoria = categorias.exigirAtiva(novo.categoria());
        // A saída leva o ID do gasto como referência; repetir a mesma referência não lança de novo (T5 de contas).
        contas.registrarSaida(novo.conta(), id.valor(), novo.valor(), novo.data());
        if (!gastos.inserirSeAusente(gasto)) {
            // Outro envio do mesmo ID gravou entre a busca e a inserção.
            return reenvio(gastos.buscar(id).orElseThrow(), gasto);
        }
        eventos.publishEvent(new GastoLancado(id, gasto.data(), gasto.valor(), gasto.categoria()));
        return new Resultado(new GastoComCategoria(gasto, categoria.nome()), true);
    }

    /** Correção do gasto vivo (RF11). No gasto em folha, só a categoria muda (RF15, RN15). */
    @Transactional
    GastoComCategoria alterar(GastoId id, AlteracaoGasto alteracao) {
        var registro = gastos.buscar(id).filter(r -> !r.excluida())
                .orElseThrow(() -> new GastoNaoEncontradoException(id));
        var atual = registro.gasto();
        var forma = switch (atual.forma()) {
            case FormaPagamento.AVista a ->
                    alteracao.conta() == null ? a : new FormaPagamento.AVista(alteracao.conta());
            case FormaPagamento.DescontoEmFolha d -> {
                if (alteracao.mexeNaConta() || alteracao.descricao() != null) {
                    throw new GastoDoHoleriteException(id);
                }
                yield d;
            }
        };

        var nomeDaCategoria = registro.categoriaNome();
        if (alteracao.categoria() != null) {
            nomeDaCategoria = categorias.exigirAtiva(alteracao.categoria()).nome();
        }
        var alterado = new Gasto(id,
                alteracao.data() == null ? atual.data() : alteracao.data(),
                alteracao.descricao() == null ? atual.descricao() : alteracao.descricao().strip(),
                alteracao.valor() == null ? atual.valor() : alteracao.valor(),
                alteracao.categoria() == null ? atual.categoria() : alteracao.categoria(),
                forma);

        if (forma instanceof FormaPagamento.AVista a && alteracao.mexeNaConta()) {
            contas.alterarLancamento(id.valor(), a.conta(), alterado.valor(), alterado.data());
        }
        gastos.alterar(alterado);
        return new GastoComCategoria(alterado, nomeDaCategoria);
    }

    /** Exclusão lógica do gasto e da saída (RF12). Inexistente ou já excluído não é erro. */
    @Transactional
    void excluir(GastoId id) {
        var registro = gastos.buscar(id);
        if (registro.isEmpty()) {
            return;
        }
        exigirAVista(id, registro.get().gasto());
        if (!registro.get().excluida()) {
            gastos.excluir(id);
            contas.excluirLancamento(id.valor());
        }
    }

    /** Desfaz as duas exclusões (RF12). Restaurar o que está vivo não muda nada. */
    @Transactional
    GastoComCategoria restaurar(GastoId id) {
        var registro = gastos.buscar(id).orElseThrow(() -> new GastoNaoEncontradoException(id));
        exigirAVista(id, registro.gasto());
        if (registro.excluida()) {
            gastos.restaurar(id);
            contas.restaurarLancamento(id.valor());
        }
        return new GastoComCategoria(registro.gasto(), registro.categoriaNome());
    }

    List<GastoComCategoria> listar(Competencia competencia, CategoriaId categoria) {
        return gastos.listar(competencia, categoria);
    }

    private static Resultado reenvio(GastoRepository.Registro existente, Gasto novo) {
        if (existente.excluida()) {
            throw new GastoExcluidoException(novo.id());
        }
        if (!existente.gasto().equals(novo)) {
            throw new GastoDivergenteException(novo.id());
        }
        return new Resultado(new GastoComCategoria(existente.gasto(), existente.categoriaNome()), false);
    }

    /** O gasto em folha some e volta com o holerite, não pela API (RN15). */
    private static void exigirAVista(GastoId id, Gasto gasto) {
        switch (gasto.forma()) {
            case FormaPagamento.AVista a -> { }
            case FormaPagamento.DescontoEmFolha d -> throw new GastoDoHoleriteException(id);
        }
    }
}
