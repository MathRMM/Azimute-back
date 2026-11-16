package br.dev.azimute.fluxo.folha;

import br.dev.azimute.ClienteDeTeste;
import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catálogo de rubricas e regras de contrapartida (RF35, RF35a, RF36, RN12, RN13, T11). Os testes usam rubricas novas
 * e vigências distantes: o catálogo e as regras não são limpos entre os testes.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class CatalogoIT {

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    ApiDaFolha api;
    String codigo;

    @BeforeEach
    void preparar() throws Exception {
        api = new ApiDaFolha(ClienteDeTeste.autenticado(contexto, jdbc), json, jdbc).comContas().configurada();
        codigo = "T" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void lista_traz_as_rubricas_do_exemplo_classificadas() throws Exception {
        api.consultar("/api/folha/rubricas").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.codigo == '/507')].natureza").value("GASTO"))
                .andExpect(jsonPath("$[?(@.codigo == '/507')].categoria").value(api.categoria("Transporte")
                        .toString()))
                .andExpect(jsonPath("$[?(@.codigo == '2363')].natureza").value("PREVIDENCIA_BASICA"))
                .andExpect(jsonPath("$[?(@.codigo == '0001')].aClassificar").value(false));
    }

    @Test
    void rubrica_desconhecida_entra_a_classificar_e_reclassificar_cria_e_remove_o_gasto_em_folha() throws Exception {
        var holerite = ApiDaFolha.holerite("2026-10");
        ApiDaFolha.acrescentar(holerite, ApiDaFolha.rubrica(codigo, "Plano de Saúde", null, "120"), "3700", "1300");
        var saude = api.categoria("Saúde");

        api.importar(holerite).andExpect(status().isCreated())
                .andExpect(jsonPath("$.avisos", hasSize(1)))
                .andExpect(jsonPath("$.avisos[0].codigo").value(codigo))
                .andExpect(jsonPath("$.avisos[0].natureza").value("OUTRO_DESCONTO"));
        api.consultar("/api/folha/rubricas")
                .andExpect(jsonPath("$[?(@.codigo == '" + codigo + "')].aClassificar").value(true));
        api.consultar("/api/gastos", "competencia", "2026-10").andExpect(jsonPath("$", hasSize(1)));

        api.reclassificar(codigo, Map.of("natureza", "GASTO", "categoria", saude.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.natureza").value("GASTO"))
                .andExpect(jsonPath("$.aClassificar").value(false));
        api.consultar("/api/gastos", "competencia", "2026-10").andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].categoriaNome", hasItem("Saúde")));

        api.reclassificar(codigo, Map.of("natureza", "OUTRO_DESCONTO")).andExpect(status().isOk());
        api.consultar("/api/gastos", "competencia", "2026-10").andExpect(jsonPath("$", hasSize(1)));

        api.reclassificar(codigo, Map.of("natureza", "GASTO", "categoria", saude.toString()))
                .andExpect(status().isOk());
        api.consultar("/api/gastos", "competencia", "2026-10").andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void rubrica_desconhecida_na_coluna_de_vencimentos_entra_como_provento() throws Exception {
        var holerite = ApiDaFolha.holerite("2026-10");
        ApiDaFolha.acrescentar(holerite, ApiDaFolha.rubrica(codigo, "Hora extra", "100", null), "3580", "1520");
        holerite.put("totalVencimentos", "5100");

        api.importar(holerite).andExpect(status().isCreated())
                .andExpect(jsonPath("$.avisos[0].natureza").value("PROVENTO"));
    }

    @Test
    void reclassificar_como_previdencia_basica_ajusta_a_entrada_e_nao_muda_a_contrapartida_gravada()
            throws Exception {
        var holerite = ApiDaFolha.holerite("2026-10");
        ApiDaFolha.acrescentar(holerite, ApiDaFolha.rubrica(codigo, "Prev extra", null, "20"), "3600", "1400");
        api.importarCriando(holerite);
        api.saldo(api.previdencia, "350.00");

        api.reclassificar(codigo, Map.of("natureza", "PREVIDENCIA_BASICA")).andExpect(status().isOk());

        api.saldo(api.previdencia, "370.00");
    }

    @Test
    void erros_da_reclassificacao() throws Exception {
        var holerite = ApiDaFolha.holerite("2026-10");
        ApiDaFolha.acrescentar(holerite, ApiDaFolha.rubrica(codigo, "Outra", null, "10"), "3590", "1410");
        api.importarCriando(holerite);
        var inativa = api.id(api.enviar(post("/api/categorias"), Map.of("nome", "Antiga " + UUID.randomUUID())));
        api.enviar(patch("/api/categorias/" + inativa), Map.of("ativa", false)).andExpect(status().isOk());

        var naturezas = "deve ser um de [PROVENTO, INSS, IRRF, PREVIDENCIA_BASICA, PREVIDENCIA_VOLUNTARIA, "
                + "ADIANTAMENTO, GASTO, OUTRO_DESCONTO]";
        api.reclassificar(codigo, Map.of()).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.natureza").value(naturezas));
        api.reclassificar(codigo, Map.of("natureza", "BONUS")).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.natureza").value(naturezas));
        api.reclassificar(codigo, Map.of("natureza", "GASTO")).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.categoria").value("obrigatória na rubrica de gasto"));
        api.reclassificar(codigo, Map.of("natureza", "INSS", "categoria", inativa.toString()))
                .andExpect(status().is(422)).andExpect(jsonPath("$.detalhes.campos.categoria")
                        .value("só a rubrica de gasto tem categoria"));
        api.reclassificar(codigo, Map.of("natureza", "GASTO", "categoria", "x")).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.categoria").value("deve ser um ID válido"));
        api.reclassificar(codigo, Map.of("natureza", "GASTO", "categoria", inativa.toString()))
                .andExpect(status().is(422)).andExpect(jsonPath("$.erro").value("CATEGORIA_INATIVA"));
        api.reclassificar(codigo, Map.of("natureza", "GASTO", "categoria", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.erro").value("CATEGORIA_NAO_ENCONTRADA"));
        api.reclassificar("NAO-EXISTE", Map.of("natureza", "INSS")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("RUBRICA_NAO_ENCONTRADA"));
    }

    @Test
    void regras_de_contrapartida_listam_e_aceitam_uma_por_vigencia() throws Exception {
        var ano = ThreadLocalRandom.current().nextInt(2100, 9000);
        var vigencia = ano + "-01";

        api.consultar("/api/folha/contrapartida").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].aPartirDe").value("2000-01"))
                .andExpect(jsonPath("$[0].percentualDaBasica").value("100.00"));
        api.enviar(post("/api/folha/contrapartida"), Map.of("aPartirDe", vigencia, "percentualDaBasica", "75.5"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aPartirDe").value(vigencia))
                .andExpect(jsonPath("$.percentualDaBasica").value("75.50"));
        // No banco a regra é fração (75,5% → 0.7550); na API, pontos (caracterização da Fase 1).
        assertThat(jdbc.sql("SELECT percentual_da_basica FROM fluxo.regras_contrapartida WHERE a_partir_de = :d")
                .param("d", java.time.LocalDate.parse(vigencia + "-01")).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("0.7550");
        api.enviar(post("/api/folha/contrapartida"), Map.of("aPartirDe", vigencia, "percentualDaBasica", "10"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("REGRA_DE_CONTRAPARTIDA_EXISTENTE"));
        api.enviar(post("/api/folha/contrapartida"), Map.of("aPartirDe", "2026-13", "percentualDaBasica", "-1"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.aPartirDe").value("deve estar no formato AAAA-MM"))
                .andExpect(jsonPath("$.detalhes.campos.percentualDaBasica")
                        .value("must be greater than or equal to 0"));
    }
}
