package br.dev.azimute.calculadora.historico;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;

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
        var existente = repositorio.buscar(id).orElseThrow();
        if (!existente.dados().mesmoConteudo(dados)) {
            throw new SimulacaoDivergenteException(id);
        }
        return existente;
    }

    public List<SimulacaoSalva> listar(String bem) {
        return repositorio.listar(bem == null || bem.isBlank() ? null : bem);
    }

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
