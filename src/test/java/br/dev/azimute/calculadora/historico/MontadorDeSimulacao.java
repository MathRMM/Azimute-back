package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.Dinheiro;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Simulação consistente para testes unitários: R$ 1.000 financiados em 1 parcela de R$ 1.010, a 1% a.m.
 * ({@code 1,01^12 − 1 = 0,12682503} a.a.). Cada teste altera só o campo que interessa.
 */
final class MontadorDeSimulacao {

    String bem = "Carro";
    LocalDate data = LocalDate.of(2026, 11, 1);
    String valorBem = "1000";
    String entrada = "0";
    String parcela = "1010";
    int prazo = 1;
    String valorFinanciado = "1000";
    String taxaMensal = "0.01";
    String taxaAnual = "0.12682503";
    String totalPago = "1010";
    String totalJuros = "10";
    OrigemSimulacao origem = OrigemSimulacao.PROPOSTA;
    SimulacaoId ancoraId;
    String observacao;
    Instant calculadaEm = Instant.parse("2026-11-01T12:00:00Z");

    static MontadorDeSimulacao proposta() {
        return new MontadorDeSimulacao();
    }

    static MontadorDeSimulacao estimativa(SimulacaoId ancora) {
        return proposta().com(m -> {
            m.origem = OrigemSimulacao.ESTIMATIVA;
            m.ancoraId = ancora;
        });
    }

    MontadorDeSimulacao com(Consumer<MontadorDeSimulacao> ajuste) {
        ajuste.accept(this);
        return this;
    }

    DadosSimulacao montar() {
        return new DadosSimulacao(bem, data, "Concessionária", "Banco", Dinheiro.de(valorBem), Dinheiro.de(entrada),
                Dinheiro.de(parcela), prazo, Dinheiro.de(valorFinanciado), new BigDecimal(taxaMensal),
                new BigDecimal(taxaAnual), Dinheiro.de(totalPago), Dinheiro.de(totalJuros), origem, ancoraId,
                observacao, calculadaEm);
    }

    SimulacaoSalva salva() {
        return salva(new SimulacaoId(UUID.randomUUID()));
    }

    SimulacaoSalva salva(SimulacaoId id) {
        return new SimulacaoSalva(id, montar(), StatusSimulacao.RECEBIDA, calculadaEm);
    }
}
