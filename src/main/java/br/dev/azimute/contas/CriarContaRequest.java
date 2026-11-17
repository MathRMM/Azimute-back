package br.dev.azimute.contas;

import br.dev.azimute.plataforma.web.ValorMonetario;
import br.dev.azimute.comum.Dinheiro;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Corpo do {@code POST /api/contas} (RF01). Os valores chegam como {@link BigDecimal} para que um valor com mais
 * de 2 casas seja recusado, em vez de arredondado em silêncio pelo {@link Dinheiro}.
 */
record CriarContaRequest(
        @NotBlank String apelido,
        @NotBlank String instituicao,
        @NotNull TipoConta tipo,
        @NotNull Funcao funcao,
        @NotNull @ValorMonetario BigDecimal saldoInicial,
        LocalDate dataSaldoInicial,
        @ValorMonetario.NaoNegativo BigDecimal limiteChequeEspecial) {

    NovaConta paraDominio() {
        return new NovaConta(apelido, instituicao, tipo, funcao, new Dinheiro(saldoInicial), dataSaldoInicial,
                limiteChequeEspecial == null ? null : new Dinheiro(limiteChequeEspecial));
    }
}
