package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Corpo do {@code PUT /api/contas/movimentacoes/{id}} (RF10, RF11). Valor positivo com até 2 casas, sem
 * arredondar (RN05). A data pode ser futura (RN15).
 */
record RegistrarMovimentacaoRequest(
        @NotBlank String tipo,
        UUID origem,
        UUID destino,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal valor,
        @NotNull LocalDate data,
        String motivo) {

    NovaMovimentacao paraDominio() {
        return new NovaMovimentacao(tipo, origem == null ? null : new ContaId(origem),
                destino == null ? null : new ContaId(destino), new Dinheiro(valor), data, motivo);
    }
}
