package br.dev.azimute.fluxo;

import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O schema {@code fluxo} recusa dado incoerente por conta própria (Fase 2 do SPEC do fluxo: RF01, RF14, RF21, RF35,
 * RF35a, RN02, RN03, RN06, RNF01, RNF08, T7, T9).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SchemaFluxoIT {

    @Autowired JdbcClient jdbc;

    private MontadorDeFluxo fluxo() {
        return new MontadorDeFluxo(jdbc);
    }

    @Test
    void schema_fluxo_existe_com_as_tabelas_do_modulo() {
        var tabelas = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'fluxo'")
                .query(String.class).list();

        assertThat(tabelas).containsExactlyInAnyOrder("categorias", "holerites", "holerite_arquivos",
                "holerite_rubricas", "rubricas_catalogo", "regras_contrapartida", "configuracao_folha", "ganhos",
                "recebimentos", "lancamentos_folha", "gastos");
    }

    @Test
    void gasto_a_vista_exige_conta_e_nao_aceita_holerite() {
        var conta = fluxo().conta();
        var holerite = fluxo().holerite();

        assertThatThrownBy(() -> fluxo().gasto("A_VISTA", null, null, "10"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("forma_coerente");
        assertThatThrownBy(() -> fluxo().gasto("A_VISTA", conta, holerite, "10"))
                .hasMessageContaining("forma_coerente");
        assertThatCode(() -> fluxo().gasto("A_VISTA", conta, null, "10")).doesNotThrowAnyException();
    }

    @Test
    void gasto_em_folha_exige_holerite_e_nao_aceita_conta() {
        var conta = fluxo().conta();
        var holerite = fluxo().holerite();

        assertThatThrownBy(() -> fluxo().gasto("DESCONTO_EM_FOLHA", conta, holerite, "10"))
                .hasMessageContaining("forma_coerente");
        assertThatThrownBy(() -> fluxo().gasto("DESCONTO_EM_FOLHA", null, null, "10"))
                .hasMessageContaining("forma_coerente");
        assertThatCode(() -> fluxo().gasto("DESCONTO_EM_FOLHA", null, holerite, "10")).doesNotThrowAnyException();
    }

    @Test
    void forma_desconhecida_e_recusada() {
        var conta = fluxo().conta();

        assertThatThrownBy(() -> fluxo().gasto("CREDITO", conta, null, "10"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gasto_com_valor_zero_ou_negativo_e_recusado() {
        var conta = fluxo().conta();

        assertThatThrownBy(() -> fluxo().gasto("A_VISTA", conta, null, "0"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fluxo().gasto("A_VISTA", conta, null, "-5"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ganho_com_liquido_maior_que_o_bruto_e_recusado() {
        assertThatThrownBy(() -> fluxo().ganho("FREELA", "100", "100.01", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("liquido_nao_supera_bruto");
        assertThatCode(() -> fluxo().ganho("FREELA", "100", "100", null)).doesNotThrowAnyException();
    }

    @Test
    void ganho_salario_so_com_holerite_e_os_outros_sem() {
        var holerite = fluxo().holerite();

        assertThatThrownBy(() -> fluxo().ganho("SALARIO", "5000", "3800", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fluxo().ganho("PLR", "5000", "3800", holerite))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fluxo().ganho("BONUS", "10", "10", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> fluxo().ganho("SALARIO", "5000", "3800", holerite)).doesNotThrowAnyException();
    }

    @Test
    void competencia_do_ganho_precisa_ser_dia_primeiro() {
        assertThatThrownBy(() -> fluxo().ganho("OUTRO", "10", "10", null, LocalDate.of(2026, 11, 15)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nome_de_categoria_repetido_com_outra_caixa_e_espacos_e_recusado() {
        var nome = "Pets " + UUID.randomUUID();
        fluxo().categoria(nome);

        assertThatThrownBy(() -> fluxo().categoria("  " + nome.toUpperCase() + " "))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("categorias_nome_unico");
        assertThatThrownBy(() -> fluxo().categoria(" lazer "))
                .hasMessageContaining("categorias_nome_unico");
        assertThatThrownBy(() -> fluxo().categoria("   "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void dois_holerites_vivos_na_mesma_competencia_sao_recusados_e_com_o_primeiro_excluido_e_aceito() {
        var competencia = MontadorDeFluxo.competenciaLivre();
        var primeiro = fluxo().holerite(competencia);

        assertThatThrownBy(() -> fluxo().holerite(competencia))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("holerites_competencia_unica");

        jdbc.sql("UPDATE fluxo.holerites SET deleted_at = now() WHERE id = :id").param("id", primeiro).update();
        assertThatCode(() -> fluxo().holerite(competencia)).doesNotThrowAnyException();
    }

    @Test
    void holerite_com_totais_incoerentes_ou_competencia_fora_do_dia_primeiro_e_recusado() {
        var inserir = (java.util.function.BiConsumer<LocalDate, String>) (comp, liquido) -> jdbc.sql("""
                        INSERT INTO fluxo.holerites (competencia, origem, hash, total_vencimentos, total_descontos,
                                                     liquido, data_liquido)
                        VALUES (:comp, 'PDF', 'h', 5000, 1200, CAST(:liquido AS NUMERIC), DATE '2026-11-30')""")
                .param("comp", comp).param("liquido", liquido).update();

        assertThatThrownBy(() -> inserir.accept(MontadorDeFluxo.competenciaLivre(), "3700"))
                .hasMessageContaining("totais_coerentes");
        assertThatThrownBy(() -> inserir.accept(LocalDate.of(2026, 11, 2), "3800"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rubrica_do_holerite_tem_vencimento_ou_desconto_nunca_os_dois() {
        var holerite = fluxo().holerite();
        var inserir = (java.util.function.BiConsumer<Integer, String[]>) (ordem, valores) -> jdbc.sql("""
                        INSERT INTO fluxo.holerite_rubricas (holerite_id, ordem, codigo, descricao, vencimento, desconto)
                        VALUES (:h, :ordem, '0001', 'Salário', CAST(:v AS NUMERIC), CAST(:d AS NUMERIC))""")
                .param("h", holerite).param("ordem", ordem).param("v", valores[0]).param("d", valores[1]).update();

        assertThatCode(() -> inserir.accept(1, new String[]{"5000", null})).doesNotThrowAnyException();
        assertThatCode(() -> inserir.accept(2, new String[]{null, "500"})).doesNotThrowAnyException();
        assertThatThrownBy(() -> inserir.accept(3, new String[]{"1", "1"}))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inserir.accept(4, new String[]{null, null}))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migration_traz_as_nove_categorias_predefinidas() {
        var nomes = jdbc.sql("SELECT nome FROM fluxo.categorias WHERE predefinida AND ativa")
                .query(String.class).list();

        assertThat(nomes).containsExactlyInAnyOrder("Alimentação", "Moradia", "Transporte", "Saúde", "Educação",
                "Lazer", "Assinaturas", "Vestuário", "Outros");
    }

    @Test
    void migration_traz_as_seis_rubricas_do_exemplo_classificadas() {
        var naturezas = jdbc.sql("""
                        SELECT r.codigo || '=' || r.natureza || coalesce(':' || c.nome, '')
                        FROM fluxo.rubricas_catalogo r LEFT JOIN fluxo.categorias c ON c.id = r.categoria_id
                        WHERE r.codigo IN ('0001', '/314', '/507', '2363', '2364', '/B02') AND NOT r.a_classificar""")
                .query(String.class).list();

        assertThat(naturezas).containsExactlyInAnyOrder("0001=PROVENTO", "/314=INSS", "/507=GASTO:Transporte",
                "2363=PREVIDENCIA_BASICA", "2364=PREVIDENCIA_VOLUNTARIA", "/B02=ADIANTAMENTO");
    }

    @Test
    void rubrica_de_gasto_exige_categoria_e_as_outras_nao_tem() {
        var categoria = fluxo().categoria();
        var inserir = (java.util.function.BiConsumer<String, UUID>) (natureza, cat) -> jdbc.sql("""
                        INSERT INTO fluxo.rubricas_catalogo (codigo, descricao, natureza, categoria_id)
                        VALUES (:codigo, 'Rubrica', :natureza, :cat)""")
                .param("codigo", UUID.randomUUID().toString()).param("natureza", natureza).param("cat", cat)
                .update();

        assertThatThrownBy(() -> inserir.accept("GASTO", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inserir.accept("INSS", categoria)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inserir.accept("BONUS", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> inserir.accept("GASTO", categoria)).doesNotThrowAnyException();
    }

    @Test
    void migration_traz_a_regra_de_contrapartida_de_cem_por_cento_e_ela_e_imutavel() {
        var percentual = jdbc.sql("""
                        SELECT percentual_da_basica FROM fluxo.regras_contrapartida
                        WHERE a_partir_de = DATE '2000-01-01'""")
                .query(BigDecimal.class).single();

        assertThat(percentual).isEqualByComparingTo("1");
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE fluxo.regras_contrapartida SET percentual_da_basica = 0.5
                        WHERE a_partir_de = DATE '2000-01-01'""").update())
                .hasMessageContaining("Registro imutável em regras_contrapartida");
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO fluxo.regras_contrapartida (a_partir_de, percentual_da_basica)
                        VALUES (DATE '2030-01-15', 1)""").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void configuracao_da_folha_tem_uma_linha_so() {
        var salario = fluxo().conta();
        var fgts = fluxo().contaDoTipo("FGTS");
        var previdencia = fluxo().contaDoTipo("PREVIDENCIA");
        jdbc.sql("DELETE FROM fluxo.configuracao_folha").update();
        var inserir = (Runnable) () -> jdbc.sql("""
                        INSERT INTO fluxo.configuracao_folha (conta_salario, conta_fgts, conta_previdencia)
                        VALUES (:s, :f, :p)""")
                .param("s", salario).param("f", fgts).param("p", previdencia).update();

        inserir.run();

        assertThatThrownBy(inserir::run).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO fluxo.configuracao_folha (id, conta_salario, conta_fgts, conta_previdencia)
                        VALUES (false, :s, :f, :p)""")
                .param("s", salario).param("f", fgts).param("p", previdencia).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("DELETE FROM fluxo.configuracao_folha").update();
    }

    @Test
    void recebimento_e_lancamento_de_folha_precisam_de_valor_positivo_e_papel_conhecido() {
        var conta = fluxo().conta();
        var holerite = fluxo().holerite();
        var recebimento = (java.util.function.BiConsumer<String, String>) (papel, valor) -> jdbc.sql("""
                        INSERT INTO fluxo.recebimentos (id, competencia, papel, valor, data, conta_id)
                        VALUES (:id, DATE '2026-11-01', :papel, CAST(:valor AS NUMERIC), DATE '2026-11-14', :conta)""")
                .param("id", UUID.randomUUID()).param("papel", papel).param("valor", valor).param("conta", conta)
                .update();
        var lancamento = (java.util.function.BiConsumer<String, String>) (papel, valor) -> jdbc.sql("""
                        INSERT INTO fluxo.lancamentos_folha (id, holerite_id, papel, valor, data, conta_id)
                        VALUES (:id, :h, :papel, CAST(:valor AS NUMERIC), DATE '2026-11-30', :conta)""")
                .param("id", UUID.randomUUID()).param("h", holerite).param("papel", papel).param("valor", valor)
                .param("conta", conta).update();

        assertThatCode(() -> recebimento.accept("ADIANTAMENTO", "2500")).doesNotThrowAnyException();
        assertThatThrownBy(() -> recebimento.accept("LIQUIDO", "0")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> recebimento.accept("BONUS", "10")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> lancamento.accept("PREV_CONTRAPARTIDA", "300")).doesNotThrowAnyException();
        assertThatThrownBy(() -> lancamento.accept("FGTS", "-1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> lancamento.accept("INSS", "10")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void datas_de_controle_sao_preenchidas_na_insercao() {
        var conta = fluxo().conta();
        var gasto = fluxo().gasto("A_VISTA", conta, null, "10");
        var ganho = fluxo().ganho("REEMBOLSO", "50", "50", null);

        var gastoOk = jdbc.sql("""
                        SELECT created_at IS NOT NULL AND updated_at IS NOT NULL AND deleted_at IS NULL
                        FROM fluxo.gastos WHERE id = :id""")
                .param("id", gasto).query(Boolean.class).single();
        var ganhoOk = jdbc.sql("""
                        SELECT created_at IS NOT NULL AND updated_at IS NOT NULL AND deleted_at IS NULL
                        FROM fluxo.ganhos WHERE id = :id""")
                .param("id", ganho).query(Boolean.class).single();
        var colunasDasRegras = jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'fluxo' AND table_name = 'regras_contrapartida'""")
                .query(String.class).list();
        var colunasDasCategorias = jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'fluxo' AND table_name = 'categorias'""")
                .query(String.class).list();

        assertThat(gastoOk).isTrue();
        assertThat(ganhoOk).isTrue();
        assertThat(colunasDasRegras).contains("created_at").doesNotContain("updated_at", "deleted_at");
        assertThat(colunasDasCategorias).contains("created_at", "updated_at").doesNotContain("deleted_at");
    }
}
