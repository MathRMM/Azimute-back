package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.Dinheiro;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Histórico de simulações: o propósito é negociação — comparar propostas depois, não de memória.
 * <p>
 * A calculadora não publica eventos: simulação não é movimento financeiro (RN13).
 */
@Service
public class HistoricoService {

    private final SimulacaoRepository repositorio;
    private final ConferenciaDeSnapshot conferencia;

    HistoricoService(SimulacaoRepository repositorio, ConferenciaDeSnapshot conferencia) {
        this.repositorio = repositorio;
        this.conferencia = conferencia;
    }

    /**
     * Grava com ID do cliente, idempotente (RF20, D2): repetição com os mesmos valores devolve o registro
     * existente; com valores diferentes, conflito.
     * <p>
     * Sem transação de propósito: a inserção é um comando só, e {@code ON CONFLICT DO NOTHING} resolve a
     * corrida entre dois envios simultâneos do mesmo ID.
     */
    public SimulacaoSalva salvar(SimulacaoId id, DadosSimulacao dados) {
        validarValores(dados);
        var ancora = resolverAncora(dados);
        conferencia.conferir(dados, ancora);

        if (repositorio.inserirSeAusente(id, dados)) {
            return repositorio.buscar(id).orElseThrow();
        }
        // O ID existe, mas a busca só enxerga as vivas: se não aparece, foi excluído e o reenvio não o
        // ressuscita (RF41, mesma regra da T9 de contas). Para voltar, existe o restaurar.
        var existente = repositorio.buscar(id).orElseThrow(() -> new SimulacaoExcluidaException(id));
        if (!existente.dados().mesmoConteudo(dados)) {
            throw new SimulacaoDivergenteException(id);
        }
        return existente;
    }

    public List<SimulacaoSalva> listar(String bem) {
        return repositorio.listar(bem == null || bem.isBlank() ? null : bem);
    }

    /** Só observação e status (RF23). Valores de simulação salva são imutáveis. */
    SimulacaoSalva anotar(SimulacaoId id, Anotacao anotacao) {
        repositorio.buscar(id).orElseThrow(() -> new SimulacaoNaoEncontradaException(id));
        repositorio.anotar(id, anotacao);
        return repositorio.buscar(id).orElseThrow(() -> new SimulacaoNaoEncontradaException(id));
    }

    /**
     * Exclusão lógica (RF41). Idempotente: excluir o que não existe ou já foi excluído não é erro (decisão T3).
     * Proposta que é âncora de estimativas vivas não pode ser excluída (D3).
     */
    void excluir(SimulacaoId id) {
        var estimativas = repositorio.estimativasAncoradasEm(id);
        if (!estimativas.isEmpty()) {
            throw new SimulacaoEAncoraException(estimativas);
        }
        if (!repositorio.excluir(id)) {
            // Nada excluído: ou não havia o que excluir, ou uma estimativa foi gravada depois da verificação
            // e o NOT EXISTS do UPDATE barrou.
            var agora = repositorio.estimativasAncoradasEm(id);
            if (!agora.isEmpty()) {
                throw new SimulacaoEAncoraException(agora);
            }
        }
    }

    /**
     * Desfaz a exclusão lógica (RF41). Restaurar a que está viva não muda nada. Estimativa com a âncora excluída
     * traz a âncora junto (decisão do usuário, 2026-11-16); restaurar a proposta não traz as estimativas.
     */
    SimulacaoSalva restaurar(SimulacaoId id) {
        repositorio.restaurar(id);
        return repositorio.buscar(id).orElseThrow(() -> new SimulacaoNaoEncontradaException(id));
    }

    /**
     * Compara as propostas de um bem (RF22, D4). A melhor é a de menor taxa mensal — a única medida comparável
     * entre prazos diferentes —, com desempate pela data mais recente. Estimativas ficam de fora por padrão:
     * comparar a própria extrapolação com uma oferta real é comparar com um número que ninguém prometeu.
     */
    Comparacao comparar(String bem, boolean incluirEstimativas) {
        if (bem == null || bem.isBlank()) {
            throw new DadosInvalidosException(Map.of("bem", "informe o bem a comparar"));
        }
        var propostas = repositorio.listar(bem).stream()
                .filter(s -> incluirEstimativas || s.dados().origem() == OrigemSimulacao.PROPOSTA)
                .sorted(MELHOR_PRIMEIRO)
                .toList();

        if (propostas.isEmpty()) {
            return new Comparacao(bem.strip(), propostas, Dinheiro.ZERO);
        }
        var melhor = propostas.getFirst();
        var pior = propostas.getLast();
        var economia = pior.dados().totalJuros().menos(melhor.dados().totalJuros());
        return new Comparacao(melhor.dados().bem(), propostas, economia);
    }

    private static final Comparator<SimulacaoSalva> MELHOR_PRIMEIRO =
            Comparator.comparing((SimulacaoSalva s) -> s.dados().taxaMensal())
                    .thenComparing(s -> s.dados().data(), Comparator.reverseOrder())
                    .thenComparing(s -> s.dados().calculadaEm(), Comparator.reverseOrder());

    /** RN10. O banco tem as mesmas regras em CHECK; aqui elas viram erro legível. */
    private static void validarValores(DadosSimulacao d) {
        var campos = new LinkedHashMap<String, Object>();
        if (d.bem().isEmpty()) campos.put("bem", "não deve estar em branco");
        if (!d.valorBem().positivo()) campos.put("valorBem", "deve ser maior que zero");
        if (d.entrada().negativo()) campos.put("entrada", "não pode ser negativa");
        else if (d.entrada().compareTo(d.valorBem()) >= 0) campos.put("entrada", "deve ser menor que o valor do bem");
        if (!d.parcela().positivo()) campos.put("parcela", "deve ser maior que zero");
        if (d.prazo() < 1) campos.put("prazo", "deve ser de pelo menos 1 mês");
        if (d.taxaMensal().signum() < 0) campos.put("taxaMensal", "não pode ser negativa");
        if (d.taxaAnual().signum() < 0) campos.put("taxaAnual", "não pode ser negativa");
        if (!campos.isEmpty()) {
            throw new DadosInvalidosException(campos);
        }
    }

    /** RN11: toda estimativa aponta para a proposta de onde a taxa veio; proposta não tem âncora. */
    private SimulacaoSalva resolverAncora(DadosSimulacao d) {
        return switch (d.origem()) {
            case PROPOSTA -> {
                if (d.ancoraId() != null) throw new AncoraInvalidaException("Proposta não tem âncora");
                yield null;
            }
            case ESTIMATIVA -> {
                if (d.ancoraId() == null) {
                    throw new AncoraInvalidaException("Estimativa precisa indicar a proposta de onde a taxa veio");
                }
                var ancora = repositorio.buscar(d.ancoraId())
                        .orElseThrow(() -> new AncoraInvalidaException("Proposta âncora não encontrada"));
                if (ancora.dados().origem() != OrigemSimulacao.PROPOSTA) {
                    throw new AncoraInvalidaException("A âncora de uma estimativa precisa ser uma proposta");
                }
                yield ancora;
            }
        };
    }
}
