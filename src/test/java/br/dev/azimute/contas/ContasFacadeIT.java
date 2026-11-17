package br.dev.azimute.contas;

import br.dev.azimute.TestcontainersConfig;
import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A fachada que {@code fluxo} e {@code cartoes} vão usar (RF30), contra o Postgres real: sentido certo,
 * idempotência por referência (T5) e conta inativa recusada (RN08).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ContasFacadeIT {

    private static final LocalDate DATA = LocalDate.of(2026, 11, 5);

    @Autowired ContasFacade fachada;
    @Autowired ContaService servico;
    @Autowired JdbcClient jdbc;

    ContaId nubank;
    ContaId cdb;

    @BeforeEach
    void preparar() {
        jdbc.sql("TRUNCATE contas.conciliacoes, contas.movimentacoes, contas.contas CASCADE").update();
        nubank = criar("Nubank", TipoConta.CORRENTE, Funcao.GIRO);
        cdb = criar("CDB", TipoConta.INVESTIMENTO, Funcao.RESERVA);
    }

    @Test
    void saida_debita_a_conta_e_repetir_o_mesmo_gasto_nao_debita_de_novo() {
        var gasto = UUID.randomUUID();

        fachada.registrarSaida(nubank, gasto, Dinheiro.de("120"), DATA);
        fachada.registrarSaida(nubank, gasto, Dinheiro.de("120"), DATA);

        assertThat(fachada.saldoAtual(nubank)).isEqualTo(Dinheiro.de("880"));
        assertThat(quantidade()).isEqualTo(1);
    }

    @Test
    void movimentacao_criada_pela_fachada_nasce_com_uuid_v7() {
        fachada.registrarSaida(nubank, UUID.randomUUID(), Dinheiro.de("10"), DATA);

        var id = jdbc.sql("SELECT id FROM contas.movimentacoes WHERE tipo = 'SAIDA'").query(UUID.class).single();
        assertThat(id.version()).isEqualTo(7);
    }

    @Test
    void entrada_credita_e_pagamento_de_fatura_debita_ambos_idempotentes() {
        var ganho = UUID.randomUUID();
        var cartao = UUID.randomUUID();

        fachada.registrarEntrada(nubank, ganho, Dinheiro.de("5000"), DATA);
        fachada.registrarEntrada(nubank, ganho, Dinheiro.de("5000"), DATA);
        fachada.registrarPagamentoFatura(nubank, cartao, Competencia.de("2026-10"), Dinheiro.de("2340"), DATA);
        fachada.registrarPagamentoFatura(nubank, cartao, Competencia.de("2026-10"), Dinheiro.de("2340"), DATA);

        assertThat(fachada.saldoAtual(nubank)).isEqualTo(Dinheiro.de("3660"));
        assertThat(quantidade()).isEqualTo(2);
    }

    @Test
    void outra_fatura_do_mesmo_cartao_e_outra_movimentacao() {
        var cartao = UUID.randomUUID();

        fachada.registrarPagamentoFatura(nubank, cartao, Competencia.de("2026-10"), Dinheiro.de("100"), DATA);
        fachada.registrarPagamentoFatura(nubank, cartao, Competencia.de("2026-11"), Dinheiro.de("100"), DATA);

        assertThat(fachada.saldoAtual(nubank)).isEqualTo(Dinheiro.de("800"));
    }

    @Test
    void contas_de_reserva_traz_so_as_de_reserva_ativas() {
        var poupanca = criar("Poupança", TipoConta.POUPANCA, Funcao.RESERVA);
        servico.alterar(poupanca, new AlteracaoConta(null, null, null, null, false));

        assertThat(fachada.contasDeReserva()).extracting(Conta::id).containsExactly(cdb);
    }

    @Test
    void conta_inativa_ou_inexistente_e_recusada() {
        servico.alterar(cdb, new AlteracaoConta(null, null, null, null, false));

        assertThatThrownBy(() -> fachada.registrarEntrada(cdb, UUID.randomUUID(), Dinheiro.de("1"), DATA))
                .isInstanceOf(ContaInativaException.class);
        assertThatThrownBy(() -> fachada.registrarSaida(new ContaId(UUID.randomUUID()), UUID.randomUUID(),
                Dinheiro.de("1"), DATA)).isInstanceOf(ContaNaoEncontradaException.class);
        assertThatThrownBy(() -> fachada.saldoAtual(new ContaId(UUID.randomUUID())))
                .isInstanceOf(ContaNaoEncontradaException.class);
        assertThat(quantidade()).isZero();
    }

    private ContaId criar(String apelido, TipoConta tipo, Funcao funcao) {
        return servico.criar(new NovaConta(apelido, "Banco", tipo, funcao, Dinheiro.de("1000"),
                LocalDate.of(2026, 11, 1), null)).conta().id();
    }

    private long quantidade() {
        return jdbc.sql("SELECT count(*) FROM contas.movimentacoes").query(Long.class).single();
    }
}
