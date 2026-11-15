package br.dev.azimute.contas;

import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O banco recusa dado incoerente por conta própria (RN04, RN05, RN07, RN09, RN14, T5–T7).
 * Cada teste usa contas novas, com apelido aleatório: o banco é compartilhado entre as classes de teste.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SchemaContasIT {

    private static final LocalDate HOJE = LocalDate.of(2026, 11, 14);

    @Autowired JdbcClient jdbc;

    private MontadorDeConta contas() {
        return new MontadorDeConta(jdbc);
    }

    @Test
    void schema_contas_existe_com_as_tres_tabelas() {
        var tabelas = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'contas'")
                .query(String.class).list();

        assertThat(tabelas).containsExactlyInAnyOrder("contas", "movimentacoes", "conciliacoes");
    }

    @Test
    void transferencia_para_a_mesma_conta_viola_a_direcao() {
        var nubank = contas().corrente();

        assertThatThrownBy(() -> movimentacao("TRANSFERENCIA", nubank, nubank, "10"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("direcao_coerente");
    }

    @Test
    void saida_com_destino_viola_a_direcao() {
        var nubank = contas().corrente();
        var cdb = contas().investimento();

        assertThatThrownBy(() -> movimentacao("SAIDA", nubank, cdb, "10"))
                .hasMessageContaining("direcao_coerente");
    }

    @Test
    void entrada_sem_destino_viola_a_direcao() {
        var nubank = contas().corrente();

        assertThatThrownBy(() -> movimentacao("ENTRADA", nubank, null, "10"))
                .hasMessageContaining("direcao_coerente");
    }

    @Test
    void cada_tipo_na_direcao_certa_e_aceito() {
        var nubank = contas().corrente();
        var cdb = contas().investimento();

        assertThatCode(() -> {
            movimentacao("ENTRADA", null, nubank, "10");
            movimentacao("RENDIMENTO", null, cdb, "10");
            movimentacao("SAIDA", nubank, null, "10");
            movimentacao("PAGAMENTO_FATURA", nubank, null, "10");
            movimentacao("TRANSFERENCIA", nubank, cdb, "10");
        }).doesNotThrowAnyException();
    }

    @Test
    void valor_zero_ou_negativo_e_recusado() {
        var cdb = contas().investimento();

        assertThatThrownBy(() -> movimentacao("RENDIMENTO", null, cdb, "0"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> movimentacao("RENDIMENTO", null, cdb, "-5"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void conciliacao_nao_pode_ser_alterada_nem_excluida() {
        var nubank = contas().corrente();
        var conciliacao = contas().conciliar(nubank, HOJE, "1000");

        assertThatThrownBy(() -> jdbc.sql("UPDATE contas.conciliacoes SET justificativa = 'x' WHERE id = :id")
                .param("id", conciliacao).update())
                .hasMessageContaining("Registro imutável em conciliacoes");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM contas.conciliacoes WHERE id = :id")
                .param("id", conciliacao).update())
                .hasMessageContaining("Registro imutável em conciliacoes");
    }

    @Test
    void duas_conciliacoes_da_mesma_conta_no_mesmo_dia_sao_recusadas() {
        var nubank = contas().corrente();
        contas().conciliar(nubank, HOJE, "1000");

        assertThatThrownBy(() -> contas().conciliar(nubank, HOJE, "900"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void apelido_repetido_com_outra_caixa_e_espacos_e_recusado() {
        var apelido = "Nubank " + UUID.randomUUID();
        contas().comApelido(apelido).corrente();

        assertThatThrownBy(() -> contas().comApelido("  " + apelido.toUpperCase() + " ").corrente())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("contas_apelido_unico");
    }

    @Test
    void apelido_em_branco_e_recusado() {
        assertThatThrownBy(() -> contas().comApelido("   ").corrente())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void limite_de_cheque_especial_so_em_conta_corrente() {
        assertThatThrownBy(() -> contas().comLimite("500").poupanca())
                .hasMessageContaining("limite_so_em_corrente");
        assertThatThrownBy(() -> contas().comLimite("-1").corrente())
                .hasMessageContaining("limite_so_em_corrente");
        assertThatCode(() -> contas().comLimite("500").corrente()).doesNotThrowAnyException();
    }

    @Test
    void mesmo_gasto_so_gera_uma_saida_viva() {
        var nubank = contas().corrente();
        var gasto = UUID.randomUUID();
        var primeira = saidaDoGasto(nubank, gasto);

        assertThatThrownBy(() -> saidaDoGasto(nubank, gasto))
                .hasMessageContaining("movimentacoes_gasto_unico");

        jdbc.sql("UPDATE contas.movimentacoes SET deleted_at = now() WHERE id = :id").param("id", primeira).update();
        assertThatCode(() -> saidaDoGasto(nubank, gasto)).doesNotThrowAnyException();
    }

    @Test
    void mesmo_ganho_e_mesma_fatura_so_geram_uma_movimentacao_viva() {
        var nubank = contas().corrente();
        var ganho = UUID.randomUUID();
        var cartao = UUID.randomUUID();
        var inserirEntrada = (Runnable) () -> jdbc.sql("""
                        INSERT INTO contas.movimentacoes (tipo, conta_destino, valor, data, ganho_id)
                        VALUES ('ENTRADA', :conta, 10, :data, :ganho)""")
                .param("conta", nubank).param("data", HOJE).param("ganho", ganho).update();
        var inserirFatura = (Runnable) () -> jdbc.sql("""
                        INSERT INTO contas.movimentacoes (tipo, conta_origem, valor, data, cartao_id, fatura_comp)
                        VALUES ('PAGAMENTO_FATURA', :conta, 10, :data, :cartao, DATE '2026-11-01')""")
                .param("conta", nubank).param("data", HOJE).param("cartao", cartao).update();

        inserirEntrada.run();
        inserirFatura.run();

        assertThatThrownBy(inserirEntrada::run).hasMessageContaining("movimentacoes_ganho_unico");
        assertThatThrownBy(inserirFatura::run).hasMessageContaining("movimentacoes_fatura_unica");
    }

    @Test
    void competencia_da_fatura_precisa_ser_dia_primeiro() {
        var nubank = contas().corrente();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO contas.movimentacoes (tipo, conta_origem, valor, data, cartao_id, fatura_comp)
                        VALUES ('PAGAMENTO_FATURA', :conta, 10, :data, :cartao, DATE '2026-11-15')""")
                .param("conta", nubank).param("data", HOJE).param("cartao", UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void datas_de_controle_sao_preenchidas_na_insercao() {
        var nubank = contas().corrente();
        var cdb = contas().investimento();
        var mov = movimentacao("TRANSFERENCIA", nubank, cdb, "10");
        contas().conciliar(nubank, HOJE, "0");

        var conta = jdbc.sql("SELECT created_at IS NOT NULL AND updated_at IS NOT NULL FROM contas.contas WHERE id = :id")
                .param("id", nubank).query(Boolean.class).single();
        var movimentacao = jdbc.sql("""
                        SELECT created_at IS NOT NULL AND updated_at IS NOT NULL AND deleted_at IS NULL
                        FROM contas.movimentacoes WHERE id = :id""")
                .param("id", mov).query(Boolean.class).single();
        var colunasDaConciliacao = jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'contas' AND table_name = 'conciliacoes'""")
                .query(String.class).list();

        assertThat(conta).isTrue();
        assertThat(movimentacao).isTrue();
        assertThat(colunasDaConciliacao).contains("created_at").doesNotContain("updated_at", "deleted_at");
    }

    private UUID movimentacao(String tipo, UUID origem, UUID destino, String valor) {
        return jdbc.sql("""
                        INSERT INTO contas.movimentacoes (tipo, conta_origem, conta_destino, valor, data)
                        VALUES (:tipo, :origem, :destino, CAST(:valor AS NUMERIC), :data) RETURNING id""")
                .param("tipo", tipo).param("origem", origem).param("destino", destino)
                .param("valor", valor).param("data", HOJE)
                .query(UUID.class).single();
    }

    private UUID saidaDoGasto(UUID conta, UUID gasto) {
        return jdbc.sql("""
                        INSERT INTO contas.movimentacoes (tipo, conta_origem, valor, data, gasto_id)
                        VALUES ('SAIDA', :conta, 10, :data, :gasto) RETURNING id""")
                .param("conta", conta).param("data", HOJE).param("gasto", gasto)
                .query(UUID.class).single();
    }
}
