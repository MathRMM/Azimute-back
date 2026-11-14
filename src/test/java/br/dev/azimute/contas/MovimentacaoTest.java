package br.dev.azimute.contas;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static br.dev.azimute.contas.Movimentacao.Sentido.CREDITO;
import static br.dev.azimute.contas.Movimentacao.Sentido.DEBITO;
import static br.dev.azimute.contas.Movimentacao.Sentido.NAO_ENVOLVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RN02, RN03, RNF02: o sentido de cada movimentação do ponto de vista de uma conta. */
class MovimentacaoTest {

    private static final ContaId NUBANK = novaConta();
    private static final ContaId CDB = novaConta();
    private static final ContaId OUTRA = novaConta();
    private static final Dinheiro CEM = Dinheiro.de("100");
    private static final LocalDate HOJE = LocalDate.of(2026, 11, 14);

    @Test
    void transferencia_debita_a_origem_credita_o_destino_e_nao_envolve_terceiros() {
        var transferencia = new Movimentacao.Transferencia(novoId(), NUBANK, CDB, CEM, HOJE, "reserva");

        assertThat(transferencia.sentidoPara(NUBANK)).isEqualTo(DEBITO);
        assertThat(transferencia.sentidoPara(CDB)).isEqualTo(CREDITO);
        assertThat(transferencia.sentidoPara(OUTRA)).isEqualTo(NAO_ENVOLVE);
    }

    @Test
    void entrada_e_rendimento_sao_credito_no_destino() {
        var entrada = new Movimentacao.Entrada(novoId(), NUBANK, UUID.randomUUID(), CEM, HOJE);
        var rendimento = new Movimentacao.Rendimento(novoId(), CDB, CEM, HOJE);

        assertThat(entrada.sentidoPara(NUBANK)).isEqualTo(CREDITO);
        assertThat(entrada.sentidoPara(CDB)).isEqualTo(NAO_ENVOLVE);
        assertThat(rendimento.sentidoPara(CDB)).isEqualTo(CREDITO);
        assertThat(rendimento.sentidoPara(NUBANK)).isEqualTo(NAO_ENVOLVE);
    }

    @Test
    void saida_e_pagamento_de_fatura_sao_debito_na_origem() {
        var saida = new Movimentacao.Saida(novoId(), NUBANK, UUID.randomUUID(), CEM, HOJE);
        var fatura = new Movimentacao.PagamentoFatura(novoId(), NUBANK, UUID.randomUUID(),
                Competencia.de("2026-11"), CEM, HOJE);

        assertThat(saida.sentidoPara(NUBANK)).isEqualTo(DEBITO);
        assertThat(saida.sentidoPara(CDB)).isEqualTo(NAO_ENVOLVE);
        assertThat(fatura.sentidoPara(NUBANK)).isEqualTo(DEBITO);
        assertThat(fatura.sentidoPara(CDB)).isEqualTo(NAO_ENVOLVE);
    }

    @Test
    void efeito_no_saldo_tem_o_sinal_do_sentido() {
        var transferencia = new Movimentacao.Transferencia(novoId(), NUBANK, CDB, CEM, HOJE, null);

        assertThat(transferencia.efeitoNoSaldo(NUBANK)).isEqualTo(Dinheiro.de("-100"));
        assertThat(transferencia.efeitoNoSaldo(CDB)).isEqualTo(CEM);
        assertThat(transferencia.efeitoNoSaldo(OUTRA)).isEqualTo(Dinheiro.ZERO);
    }

    @Test
    void valor_precisa_ser_positivo() {
        assertThatThrownBy(() -> new Movimentacao.Rendimento(novoId(), CDB, Dinheiro.ZERO, HOJE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Movimentacao.Rendimento(novoId(), CDB, Dinheiro.de("-1"), HOJE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transferencia_para_a_mesma_conta_nao_existe_nem_no_modelo() {
        assertThatThrownBy(() -> new Movimentacao.Transferencia(novoId(), NUBANK, NUBANK, CEM, HOJE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tipo_de_cada_movimentacao_corresponde_ao_valor_gravado_no_banco() {
        assertThat(new Movimentacao.Entrada(novoId(), NUBANK, null, CEM, HOJE).tipo()).isEqualTo("ENTRADA");
        assertThat(new Movimentacao.Saida(novoId(), NUBANK, null, CEM, HOJE).tipo()).isEqualTo("SAIDA");
        assertThat(new Movimentacao.Transferencia(novoId(), NUBANK, CDB, CEM, HOJE, null).tipo())
                .isEqualTo("TRANSFERENCIA");
        assertThat(new Movimentacao.PagamentoFatura(novoId(), NUBANK, null, Competencia.de("2026-11"), CEM, HOJE)
                .tipo()).isEqualTo("PAGAMENTO_FATURA");
        assertThat(new Movimentacao.Rendimento(novoId(), CDB, CEM, HOJE).tipo()).isEqualTo("RENDIMENTO");
    }

    @Test
    void identificadores_recusam_nulo() {
        assertThatThrownBy(() -> new ContaId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MovimentacaoId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConciliacaoId(null)).isInstanceOf(NullPointerException.class);
        assertThat(NUBANK.toString()).isEqualTo(NUBANK.valor().toString());
    }

    private static ContaId novaConta() {
        return new ContaId(UUID.randomUUID());
    }

    private static MovimentacaoId novoId() {
        return new MovimentacaoId(UUID.randomUUID());
    }
}
