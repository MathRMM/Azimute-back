package br.dev.azimute.calculadora.historico;

import java.time.Instant;

/** Simulação persistida. Os valores são snapshot: nunca recalculados na leitura (RF26). */
public record SimulacaoSalva(
        SimulacaoId id,
        DadosSimulacao dados,
        StatusSimulacao status,
        Instant sincronizadaEm) {
}
