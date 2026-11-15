package br.dev.azimute.calculadora.historico;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Histórico de simulações. Não há endpoint de cálculo: o cálculo acontece no cliente (RN14).
 */
@RestController
@RequestMapping("/api/calculadora/historico")
class HistoricoController {

    private static final Set<String> CAMPOS_ANOTATIVOS = Set.of("observacao", "status");

    private final HistoricoService servico;

    HistoricoController(HistoricoService servico) {
        this.servico = servico;
    }

    /** PUT com ID do cliente: idempotente; repetição devolve 200, não 409 (RF20). */
    @PutMapping("/{id}")
    SimulacaoResponse salvar(@PathVariable UUID id, @Valid @RequestBody SalvarSimulacaoRequest corpo) {
        return SimulacaoResponse.de(servico.salvar(new SimulacaoId(id), corpo.paraDominio()));
    }

    @GetMapping
    List<SimulacaoResponse> listar(@RequestParam(required = false) String bem) {
        return servico.listar(bem).stream().map(SimulacaoResponse::de).toList();
    }

    @GetMapping("/comparar")
    ComparacaoResponse comparar(@RequestParam(required = false) String bem,
                                @RequestParam(defaultValue = "false") boolean incluirEstimativas) {
        return ComparacaoResponse.de(servico.comparar(bem, incluirEstimativas));
    }

    /**
     * Só {@code observacao} e {@code status} (RF23). O corpo é lido como mapa para distinguir campo ausente
     * (não muda) de campo nulo (apaga a observação) e para nomear qualquer campo proibido.
     */
    @PatchMapping("/{id}")
    SimulacaoResponse anotar(@PathVariable UUID id, @RequestBody Map<String, Object> corpo) {
        var proibidos = corpo.keySet().stream().filter(c -> !CAMPOS_ANOTATIVOS.contains(c)).sorted().toList();
        if (!proibidos.isEmpty()) {
            throw new CampoNaoEditavelException(proibidos);
        }
        return SimulacaoResponse.de(servico.anotar(new SimulacaoId(id), lerAnotacao(corpo)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void excluir(@PathVariable UUID id) {
        servico.excluir(new SimulacaoId(id));
    }

    private static Anotacao lerAnotacao(Map<String, Object> corpo) {
        var alteraObservacao = corpo.containsKey("observacao");
        var observacao = corpo.get("observacao");
        if (observacao != null && !(observacao instanceof String)) {
            throw new DadosInvalidosException(Map.of("observacao", "deve ser texto"));
        }

        StatusSimulacao status = null;
        if (corpo.containsKey("status")) {
            status = lerStatus(corpo.get("status"));
        }
        // Em branco vale como nula, como no PUT.
        var texto = observacao == null || ((String) observacao).isBlank() ? null : ((String) observacao).strip();
        return new Anotacao(alteraObservacao, texto, status);
    }

    private static StatusSimulacao lerStatus(Object valor) {
        var validos = Arrays.stream(StatusSimulacao.values()).map(Enum::name).toList();
        if (valor instanceof String texto && validos.contains(texto)) {
            return StatusSimulacao.valueOf(texto);
        }
        throw new DadosInvalidosException(Map.of("status", "deve ser um de " + validos));
    }
}
