package br.dev.azimute.calculadora.historico;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Histórico de simulações. Não há endpoint de cálculo: o cálculo acontece no cliente (RN14).
 */
@RestController
@RequestMapping("/api/calculadora/historico")
class HistoricoController {

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
}
