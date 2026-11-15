package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.contas.ContasFacade;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Ganhos avulsos (RF20–RF24). O ganho tem um recebimento só, com o mesmo ID (T5), que é a referência da entrada em
 * {@code contas}. Como no gasto, a fachada vem antes da gravação, na mesma transação (RN04).
 */
@Service
class GanhoService {

    /** O ganho e se este envio o criou (201) ou é um reenvio (200). */
    record Resultado(GanhoComRecebimentos ganho, boolean criado) {}

    private final GanhoRepository ganhos;
    private final ContasFacade contas;
    private final ApplicationEventPublisher eventos;

    GanhoService(GanhoRepository ganhos, ContasFacade contas, ApplicationEventPublisher eventos) {
        this.ganhos = ganhos;
        this.contas = contas;
        this.eventos = eventos;
    }

    /** Idempotente pelo ID do cliente, com as mesmas regras de reenvio do gasto (RF20). */
    @Transactional
    Resultado registrar(GanhoId id, NovoGanho novo) {
        exigirAvulso(novo.tipo());
        var ganho = new Ganho(id, novo.competencia(), novo.descricao().strip(), novo.tipo(), novo.bruto(),
                novo.liquido(), null);
        var recebimento = new Recebimento(id.valor(), id, novo.competencia(), Recebimento.Papel.AVULSO,
                novo.liquido(), novo.recebidoEm(), novo.conta(), false);
        var atual = new GanhoComRecebimentos(ganho, List.of(recebimento));

        var existente = ganhos.buscar(id);
        if (existente.isPresent()) {
            return reenvio(existente.get(), atual);
        }
        contas.registrarEntrada(novo.conta(), recebimento.id(), novo.liquido(), novo.recebidoEm());
        if (!ganhos.inserirSeAusente(ganho)) {
            // Outro envio do mesmo ID gravou entre a busca e a inserção.
            return reenvio(ganhos.buscar(id).orElseThrow(), atual);
        }
        ganhos.inserirRecebimento(recebimento);
        eventos.publishEvent(new GanhoRegistrado(id, ganho.competencia(), ganho.liquido()));
        return new Resultado(atual, true);
    }

    /** Correção do ganho avulso vivo; o recebimento acompanha líquido, conta e data (RF22). */
    @Transactional
    GanhoComRecebimentos alterar(GanhoId id, AlteracaoGanho alteracao) {
        var registro = ganhos.buscar(id).filter(r -> !r.excluido())
                .orElseThrow(() -> new GanhoNaoEncontradoException(id));
        var atual = registro.ganho();
        exigirDaApi(atual);
        if (alteracao.tipo() != null) {
            exigirAvulso(alteracao.tipo());
        }
        var alterado = new Ganho(id,
                alteracao.competencia() == null ? atual.competencia() : alteracao.competencia(),
                alteracao.descricao() == null ? atual.descricao() : alteracao.descricao().strip(),
                alteracao.tipo() == null ? atual.tipo() : alteracao.tipo(),
                alteracao.bruto() == null ? atual.bruto() : alteracao.bruto(),
                alteracao.liquido() == null ? atual.liquido() : alteracao.liquido(),
                null);
        var recebimento = registro.recebimentos().getFirst();
        var novoRecebimento = new Recebimento(recebimento.id(), id, alterado.competencia(), recebimento.papel(),
                alterado.liquido(), alteracao.recebidoEm() == null ? recebimento.data() : alteracao.recebidoEm(),
                alteracao.conta() == null ? recebimento.conta() : alteracao.conta(), false);

        if (alteracao.mexeNaConta()) {
            contas.alterarLancamento(novoRecebimento.id(), novoRecebimento.conta(), novoRecebimento.valor(),
                    novoRecebimento.data());
        }
        ganhos.alterar(alterado);
        ganhos.alterarRecebimento(novoRecebimento);
        return new GanhoComRecebimentos(alterado, List.of(novoRecebimento));
    }

    /** Exclusão lógica do ganho, do recebimento e da entrada (RF22). Inexistente ou já excluído não é erro. */
    @Transactional
    void excluir(GanhoId id) {
        var registro = ganhos.buscar(id);
        if (registro.isEmpty()) {
            return;
        }
        exigirDaApi(registro.get().ganho());
        if (!registro.get().excluido()) {
            ganhos.excluir(id);
            registro.get().recebimentos().forEach(r -> contas.excluirLancamento(r.id()));
        }
    }

    /** Desfaz a exclusão (RF22). Restaurar o que está vivo não muda nada. */
    @Transactional
    GanhoComRecebimentos restaurar(GanhoId id) {
        var registro = ganhos.buscar(id).orElseThrow(() -> new GanhoNaoEncontradoException(id));
        exigirDaApi(registro.ganho());
        if (registro.excluido()) {
            ganhos.restaurar(id);
            registro.recebimentos().forEach(r -> contas.restaurarLancamento(r.id()));
        }
        return new GanhoComRecebimentos(registro.ganho(), registro.recebimentos());
    }

    List<GanhoComRecebimentos> listar(Competencia competencia) {
        return ganhos.listar(competencia);
    }

    private static Resultado reenvio(GanhoRepository.Registro existente, GanhoComRecebimentos novo) {
        if (existente.excluido()) {
            throw new GanhoExcluidoException(novo.ganho().id());
        }
        var gravado = new GanhoComRecebimentos(existente.ganho(), existente.recebimentos());
        if (!gravado.equals(novo)) {
            throw new GanhoDivergenteException(novo.ganho().id());
        }
        return new Resultado(gravado, false);
    }

    /** RF21, P4: o salário nasce só do holerite. */
    private static void exigirAvulso(TipoGanho tipo) {
        if (tipo == TipoGanho.SALARIO) {
            throw new SalarioSoPeloHoleriteException();
        }
    }

    /** RN15: o ganho do holerite só muda pelo holerite. */
    private static void exigirDaApi(Ganho ganho) {
        if (ganho.holerite() != null) {
            throw new GanhoDoHoleriteException(ganho.id());
        }
    }
}
