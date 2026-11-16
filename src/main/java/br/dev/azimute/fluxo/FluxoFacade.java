package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;

/**
 * A API de {@code fluxo} para os outros módulos (RF51): {@code sistema} lê o resumo do mês na revisão da semana e a
 * {@code agenda} registra gastos pela captura rápida.
 */
public interface FluxoFacade {

    ResumoMes resumoDoMes(Competencia competencia);

    /** Mesmo caminho do {@code PUT /api/gastos/{id}}: lança a saída na conta e publica {@link GastoLancado}. */
    GastoId registrarGasto(RegistrarGasto comando);
}
