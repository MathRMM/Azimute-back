package br.dev.azimute.contas;

import br.dev.azimute.TestcontainersConfig;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O que o {@code fluxo} precisa para editar o que lançou (RF42 do PRD do fluxo, T2 e T3 do SPEC): alterar, excluir
 * e restaurar entradas e saídas pela referência, e consultar uma conta.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ContasFacadeLancamentosIT {

    private static final LocalDate DATA = LocalDate.of(2026, 11, 5);

    @Autowired ContasFacade fachada;
    @Autowired ContaService servico;
    @Autowired MovimentacaoService movimentacoes;
    @Autowired JdbcClient jdbc;

    ContaId nubank;
    ContaId inter;

    @BeforeEach
    void preparar() {
        jdbc.sql("TRUNCATE contas.conciliacoes, contas.movimentacoes, contas.contas").update();
        nubank = criar("Nubank", TipoConta.CORRENTE);
        inter = criar("Inter", TipoConta.CORRENTE);
    }

    @Test
    void alterar_a_saida_muda_valor_data_e_conta_e_mantem_o_id() {
        var gasto = UUID.randomUUID();
        fachada.registrarSaida(nubank, gasto, Dinheiro.de("120"), DATA);
        var idAntes = idDa(gasto);

        fachada.alterarLancamento(gasto, inter, Dinheiro.de("150"), DATA.plusDays(2));

        assertThat(idDa(gasto)).isEqualTo(idAntes);
        assertThat(fachada.saldoAtual(nubank)).isEqualTo(Dinheiro.de("1000"));
        assertThat(fachada.saldoAtual(inter)).isEqualTo(Dinheiro.de("850"));
        var linha = jdbc.sql("SELECT data, valor, updated_at > created_at AS mudou FROM contas.movimentacoes "
                        + "WHERE gasto_id = :g")
                .param("g", gasto)
                .query((rs, n) -> new Object[]{rs.getObject("data", LocalDate.class), rs.getBigDecimal("valor"),
                        rs.getBoolean("mudou")})
                .single();
        assertThat(linha[0]).isEqualTo(DATA.plusDays(2));
        assertThat(Dinheiro.de(linha[1].toString())).isEqualTo(Dinheiro.de("150"));
        assertThat(linha[2]).isEqualTo(true);
    }

    @Test
    void alterar_a_entrada_credita_a_nova_conta() {
        var ganho = UUID.randomUUID();
        fachada.registrarEntrada(nubank, ganho, Dinheiro.de("5000"), DATA);

        fachada.alterarLancamento(ganho, inter, Dinheiro.de("4800"), DATA);

        assertThat(fachada.saldoAtual(nubank)).isEqualTo(Dinheiro.de("1000"));
        assertThat(fachada.saldoAtual(inter)).isEqualTo(Dinheiro.de("5800"));
    }

    @Test
    void alterar_para_conta_inativa_ou_inexistente_e_recusado_e_nada_muda() {
        var gasto = UUID.randomUUID();
        fachada.registrarSaida(nubank, gasto, Dinheiro.de("120"), DATA);
        servico.alterar(inter, new AlteracaoConta(null, null, null, null, false));

        assertThatThrownBy(() -> fachada.alterarLancamento(gasto, inter, Dinheiro.de("1"), DATA))
                .isInstanceOf(ContaInativaException.class);
        assertThatThrownBy(() -> fachada.alterarLancamento(gasto, new ContaId(UUID.randomUUID()),
                Dinheiro.de("1"), DATA)).isInstanceOf(ContaNaoEncontradaException.class);
        assertThat(fachada.saldoAtual(nubank)).isEqualTo(Dinheiro.de("880"));
    }

    @Test
    void alterar_referencia_inexistente_ou_excluida_e_404() {
        var gasto = UUID.randomUUID();
        fachada.registrarSaida(nubank, gasto, Dinheiro.de("120"), DATA);
        fachada.excluirLancamento(gasto);

        assertThatThrownBy(() -> fachada.alterarLancamento(UUID.randomUUID(), nubank, Dinheiro.de("1"), DATA))
                .isInstanceOf(LancamentoNaoEncontradoException.class);
        assertThatThrownBy(() -> fachada.alterarLancamento(gasto, nubank, Dinheiro.de("1"), DATA))
                .isInstanceOf(LancamentoNaoEncontradoException.class);
    }

    @Test
    void excluir_e_restaurar_pela_referencia() {
        var gasto = UUID.randomUUID();
        fachada.registrarSaida(nubank, gasto, Dinheiro.de("120"), DATA);

        fachada.excluirLancamento(gasto);
        assertThat(fachada.saldoAtual(nubank)).isEqualTo(Dinheiro.de("1000"));
        assertThatCode(() -> fachada.excluirLancamento(gasto)).doesNotThrowAnyException();
        assertThatCode(() -> fachada.excluirLancamento(UUID.randomUUID())).doesNotThrowAnyException();

        fachada.restaurarLancamento(gasto);
        assertThat(fachada.saldoAtual(nubank)).isEqualTo(Dinheiro.de("880"));
        assertThatCode(() -> fachada.restaurarLancamento(gasto)).doesNotThrowAnyException();
        assertThat(fachada.saldoAtual(nubank)).isEqualTo(Dinheiro.de("880"));
    }

    @Test
    void restaurar_referencia_que_nao_existe_e_404() {
        assertThatThrownBy(() -> fachada.restaurarLancamento(UUID.randomUUID()))
                .isInstanceOf(LancamentoNaoEncontradoException.class);
    }

    @Test
    void relancar_depois_de_excluir_e_restaurar_traz_a_mais_recente_sem_duplicar() {
        var ganho = UUID.randomUUID();
        fachada.registrarEntrada(nubank, ganho, Dinheiro.de("100"), DATA);
        fachada.excluirLancamento(ganho);
        fachada.registrarEntrada(nubank, ganho, Dinheiro.de("200"), DATA);

        fachada.restaurarLancamento(ganho);

        assertThat(fachada.saldoAtual(nubank)).isEqualTo(Dinheiro.de("1200"));
        fachada.excluirLancamento(ganho);
        fachada.restaurarLancamento(ganho);
        assertThat(fachada.saldoAtual(nubank)).isEqualTo(Dinheiro.de("1200"));
    }

    @Test
    void transferencia_nao_e_alcancada_pela_referencia() {
        var id = new MovimentacaoId(UUID.randomUUID());
        movimentacoes.registrar(id, new NovaMovimentacao("TRANSFERENCIA", nubank, inter, Dinheiro.de("300"), DATA,
                null));

        assertThatThrownBy(() -> fachada.alterarLancamento(id.valor(), nubank, Dinheiro.de("1"), DATA))
                .isInstanceOf(LancamentoNaoEncontradoException.class);
        fachada.excluirLancamento(id.valor());
        assertThatThrownBy(() -> fachada.restaurarLancamento(id.valor()))
                .isInstanceOf(LancamentoNaoEncontradoException.class);
        assertThat(fachada.saldoAtual(nubank)).isEqualTo(Dinheiro.de("700"));
    }

    @Test
    void buscar_conta_traz_ativa_ou_inativa_e_vazio_quando_nao_existe() {
        var fgts = criar("FGTS", TipoConta.FGTS);
        servico.alterar(fgts, new AlteracaoConta(null, null, null, null, false));

        assertThat(fachada.buscarConta(nubank)).hasValueSatisfying(c -> assertThat(c.ativa()).isTrue());
        assertThat(fachada.buscarConta(fgts)).hasValueSatisfying(c -> {
            assertThat(c.tipo()).isEqualTo(TipoConta.FGTS);
            assertThat(c.ativa()).isFalse();
        });
        assertThat(fachada.buscarConta(new ContaId(UUID.randomUUID()))).isEmpty();
    }

    private ContaId criar(String apelido, TipoConta tipo) {
        return servico.criar(new NovaConta(apelido, "Banco", tipo, Funcao.GIRO, Dinheiro.de("1000"),
                LocalDate.of(2026, 11, 1), null)).conta().id();
    }

    private UUID idDa(UUID gasto) {
        return jdbc.sql("SELECT id FROM contas.movimentacoes WHERE gasto_id = :g").param("g", gasto)
                .query(UUID.class).single();
    }
}
