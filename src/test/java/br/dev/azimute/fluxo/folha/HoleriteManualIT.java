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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Holerite lançado em JSON: conferência, efeitos em conta, reenvio, exclusão, contrapartida por vigência e correção
 * (RF30, RF32, RF33, RF35a, RF35b, RF38, RN04, RN10, RN11, RN14, RN15, RN18, RN19, P1).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class HoleriteManualIT {

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    ApiDaFolha api;

    @BeforeEach
    void preparar() throws Exception {
        api = new ApiDaFolha(ClienteDeTeste.autenticado(contexto, jdbc), json, jdbc).comContas();
    }

    @Test
    void importar_gera_ganho_recebimentos_fgts_previdencia_separada_e_gasto_em_folha() throws Exception {
        api.configurada();

        api.importar(ApiDaFolha.holerite("2026-10")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.competencia").value("2026-10"))
                .andExpect(jsonPath("$.origem").value("MANUAL"))
                .andExpect(jsonPath("$.liquido").value("1420.00"))
                .andExpect(jsonPath("$.dataAdiantamento").value("2026-10-15"))
                .andExpect(jsonPath("$.dataLiquido").value("2026-10-30"))
                .andExpect(jsonPath("$.impostos.inss").value("480.00"))
                .andExpect(jsonPath("$.impostos.irrf").value("0.00"))
                .andExpect(jsonPath("$.impostos.total").value("480.00"))
                .andExpect(jsonPath("$.previdencia.basica").value("50.00"))
                .andExpect(jsonPath("$.previdencia.contrapartida").value("50.00"))
                .andExpect(jsonPath("$.previdencia.voluntaria").value("250.00"))
                .andExpect(jsonPath("$.previdencia.total").value("350.00"))
                .andExpect(jsonPath("$.bases.fgtsMes").value("400.00"))
                .andExpect(jsonPath("$.rubricas", hasSize(6)))
                .andExpect(jsonPath("$.avisos", hasSize(0)));

        api.saldo(api.salario, "6920.00");
        api.saldo(api.fgts, "400.00");
        api.saldo(api.previdencia, "350.00");
        api.consultar("/api/contas/consolidado").andExpect(jsonPath("$.bloqueado").value("750.00"))
                .andExpect(jsonPath("$.posicaoGeral").value("6920.00"));
        api.consultar("/api/ganhos", "competencia", "2026-10").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tipo").value("SALARIO"))
                .andExpect(jsonPath("$[0].bruto").value("5000.00"))
                .andExpect(jsonPath("$[0].liquido").value("3920.00"))
                .andExpect(jsonPath("$[0].recebimentos[*].papel", contains("ADIANTAMENTO", "LIQUIDO")));
        api.consultar("/api/gastos", "competencia", "2026-10").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].valor").value("300.00"))
                .andExpect(jsonPath("$[0].categoriaNome").value("Transporte"))
                .andExpect(jsonPath("$[0].forma").value("DESCONTO_EM_FOLHA"))
                .andExpect(jsonPath("$[0].data").value("2026-10-30"));
    }

    @Test
    void totais_inconsistentes_e_sem_configuracao_sao_422_e_nada_e_gravado() throws Exception {
        api.importar(ApiDaFolha.holerite("2026-10")).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("FOLHA_NAO_CONFIGURADA"));

        api.configurada();
        var errado = ApiDaFolha.holerite("2026-10");
        errado.put("liquido", "1400");
        api.importar(errado).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("HOLERITE_INCONSISTENTE"))
                .andExpect(jsonPath("$.detalhes.conferencia").value("liquido"));

        assertThat(contar("fluxo.holerites")).isZero();
        assertThat(contar("fluxo.ganhos")).isZero();
        api.saldo(api.salario, "3000.00");
    }

    @Test
    void corpo_invalido_e_422_com_os_campos() throws Exception {
        api.configurada();
        var invalido = ApiDaFolha.holerite("2026-13");
        invalido.put("rubricas", List.of(ApiDaFolha.rubrica("0001", "Salário", "5000", "1"),
                ApiDaFolha.rubrica(" ", "Sem código", "1.234", null)));
        invalido.remove("bases");

        api.importar(invalido).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.competencia").value("deve estar no formato AAAA-MM"))
                .andExpect(jsonPath("$.detalhes.campos.bases").value("must not be null"))
                .andExpect(jsonPath("$.detalhes.campos['rubricas[0].valorUnico']")
                        .value("informe vencimento ou desconto, só um"))
                .andExpect(jsonPath("$.detalhes.campos['rubricas[1].codigo']").value("must not be blank"))
                .andExpect(jsonPath("$.detalhes.campos['rubricas[1].vencimento']")
                        .value("numeric value out of bounds (<17 digits>.<2 digits> expected)"));
    }

    @Test
    void reenvio_identico_e_200_outro_da_mesma_competencia_e_409_e_o_excluido_tambem() throws Exception {
        api.configurada();
        var id = api.importarCriando(ApiDaFolha.holerite("2026-10"));
        assertThat(id.version()).as("holerite novo nasce com UUID v7").isEqualTo(7);

        api.importar(ApiDaFolha.holerite("2026-10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        var outro = ApiDaFolha.holerite("2026-10");
        outro.put("dataLiquido", "2026-10-29");
        api.importar(outro).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("HOLERITE_JA_IMPORTADO"));

        api.excluir(id).andExpect(status().isNoContent());
        api.importar(ApiDaFolha.holerite("2026-10")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("HOLERITE_EXCLUIDO"));
        api.importar(outro).andExpect(status().isCreated());
        api.restaurar(id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("HOLERITE_JA_IMPORTADO"));
        api.saldo(api.salario, "6920.00");
    }

    @Test
    void excluir_tira_tudo_dos_saldos_e_restaurar_devolve() throws Exception {
        api.configurada();
        var id = api.importarCriando(ApiDaFolha.holerite("2026-10"));

        api.excluir(id).andExpect(status().isNoContent());
        api.excluir(id).andExpect(status().isNoContent());
        api.saldo(api.salario, "3000.00");
        api.saldo(api.fgts, "0.00");
        api.saldo(api.previdencia, "0.00");
        api.consultar("/api/ganhos", "competencia", "2026-10").andExpect(jsonPath("$", hasSize(0)));
        api.consultar("/api/gastos", "competencia", "2026-10").andExpect(jsonPath("$", hasSize(0)));

        api.restaurar(id).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        api.restaurar(id).andExpect(status().isOk());
        api.saldo(api.salario, "6920.00");
        api.saldo(api.fgts, "400.00");
        api.saldo(api.previdencia, "350.00");
        api.consultar("/api/gastos", "competencia", "2026-10").andExpect(jsonPath("$", hasSize(1)));
        api.restaurar(UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("HOLERITE_NAO_ENCONTRADO"));
    }

    @Test
    void ganho_do_holerite_nao_muda_pela_api_e_a_categoria_do_gasto_em_folha_e_preservada() throws Exception {
        api.configurada();
        var holerite = api.importarCriando(ApiDaFolha.holerite("2026-10"));
        var ganhoId = jdbc.sql("SELECT id FROM fluxo.ganhos").query(UUID.class).single();

        api.enviar(patch("/api/ganhos/" + ganhoId), Map.of("descricao", "x")).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("GANHO_DO_HOLERITE"));

        var gasto = jdbc.sql("SELECT id FROM fluxo.gastos").query(UUID.class).single();
        var lazer = api.categoria("Lazer");
        api.enviar(patch("/api/gastos/" + gasto), Map.of("categoria", lazer.toString())).andExpect(status().isOk());
        api.corrigirPrevidencia(holerite, "60").andExpect(status().isOk());

        api.consultar("/api/gastos", "competencia", "2026-10").andExpect(jsonPath("$[0].categoriaNome")
                .value("Lazer"));
    }

    @Test
    void holerite_sem_adiantamento_tem_um_recebimento_so() throws Exception {
        api.configurada();
        var semAdiantamento = ApiDaFolha.holerite("2026-10");
        @SuppressWarnings("unchecked")
        var rubricas = (List<Map<String, Object>>) semAdiantamento.get("rubricas");
        rubricas.removeIf(r -> r.get("codigo").equals("/B02"));
        semAdiantamento.put("totalDescontos", "1080");
        semAdiantamento.put("liquido", "3920");

        api.importar(semAdiantamento).andExpect(status().isCreated());

        api.consultar("/api/ganhos", "competencia", "2026-10")
                .andExpect(jsonPath("$[0].recebimentos[*].papel", contains("LIQUIDO")))
                .andExpect(jsonPath("$[0].liquido").value("3920.00"));
        api.saldo(api.salario, "6920.00");
    }

    @Test
    void datas_informadas_prevalecem() throws Exception {
        api.configurada();
        var holerite = ApiDaFolha.holerite("2026-10");
        holerite.put("dataAdiantamento", "2026-10-16");
        holerite.put("dataLiquido", "2026-11-02");

        api.importar(holerite).andExpect(status().isCreated())
                .andExpect(jsonPath("$.dataAdiantamento").value("2026-10-16"))
                .andExpect(jsonPath("$.dataLiquido").value("2026-11-02"));
    }

    @Test
    void configuracao_com_conta_de_tipo_errado_ou_inexistente_e_recusada() throws Exception {
        api.configurar(api.salario, api.salario, api.previdencia).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("TIPO_DE_CONTA_INVALIDO"))
                .andExpect(jsonPath("$.detalhes.campo").value("contaFgts"));
        api.configurar(api.salario, api.fgts, api.fgts).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campo").value("contaPrevidencia"));
        api.configurar(api.fgts, api.fgts, api.previdencia).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campo").value("contaSalario"));
        api.configurar(UUID.randomUUID(), api.fgts, api.previdencia).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("CONTA_NAO_ENCONTRADA"));
        api.enviar(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/folha/configuracao"),
                Map.of()).andExpect(status().is(422));

        api.configurada();
        api.configurar(api.salario, api.fgts, api.previdencia).andExpect(status().isOk())
                .andExpect(jsonPath("$.contaFgts").value(api.fgts.toString()));
    }

    @Test
    void regra_de_contrapartida_vale_da_vigencia_em_diante_e_nao_muda_o_passado() throws Exception {
        api.configurada();
        var dezembro = api.importarCriando(ApiDaFolha.holerite("2089-12"));

        api.enviar(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/folha/contrapartida"),
                Map.of("aPartirDe", "2090-01", "percentualDaBasica", "50")).andExpect(status().isCreated());
        api.importar(ApiDaFolha.holerite("2090-01")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.previdencia.contrapartida").value("25.00"));

        assertThat(contrapartidaDe(dezembro)).isEqualByComparingTo("50");
        api.saldo(api.previdencia, "675.00");
    }

    @Test
    void corrigir_a_contrapartida_ajusta_so_aquele_holerite() throws Exception {
        api.configurada();
        var outubro = api.importarCriando(ApiDaFolha.holerite("2026-10"));
        var novembro = api.importarCriando(ApiDaFolha.holerite("2026-11"));

        api.corrigirPrevidencia(outubro, "80").andExpect(status().isOk())
                .andExpect(jsonPath("$.previdencia.contrapartida").value("80.00"));
        api.saldo(api.previdencia, "730.00");
        assertThat(contrapartidaDe(novembro)).isEqualByComparingTo("50");

        api.corrigirPrevidencia(outubro, "0").andExpect(status().isOk());
        api.saldo(api.previdencia, "650.00");
        for (var invalido : List.of("-1", "1.234", "abc")) {
            api.corrigirPrevidencia(outubro, invalido).andExpect(status().is(422))
                    .andExpect(jsonPath("$.detalhes.campos.contrapartida")
                            .value("deve ser um valor de zero para cima com até 2 casas decimais"));
        }
        api.corrigirPrevidencia(UUID.randomUUID(), "1").andExpect(status().isNotFound());
    }

    private long contar(String tabela) {
        return jdbc.sql("SELECT count(*) FROM " + tabela).query(Long.class).single();
    }

    private BigDecimal contrapartidaDe(UUID holerite) {
        return jdbc.sql("SELECT contrapartida_previdencia FROM fluxo.holerites WHERE id = :id")
                .param("id", holerite).query(BigDecimal.class).single();
    }
}
