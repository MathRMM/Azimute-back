package br.dev.azimute.calculadora.historico;

import br.dev.azimute.ClienteDeTeste;
import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gravação do histórico: PUT idempotente com ID do cliente, conferência do snapshot e imutabilidade no banco.
 * Refs: RF20, RF21, RF25–RF28, RN09–RN11, D2, D6.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class HistoricoGravacaoIT {

    private static final String LISTA = "/api/calculadora/historico";
    private static final String BASE = LISTA + "/";

    @Autowired WebApplicationContext contexto;
    MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired tools.jackson.databind.json.JsonMapper json;

    @BeforeEach
    void limpar() {
        mvc = ClienteDeTeste.autenticado(contexto, jdbc);
        jdbc.sql("TRUNCATE calculadora.simulacoes").update();
    }

    /** Proposta real da moto 36x, como o app enviaria (taxa com 8 casas). */
    static Map<String, Object> proposta() {
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("bem", "Honda CB 300F");
        corpo.put("data", "2026-09-12");
        corpo.put("local", "Concessionária X, Campinas");
        corpo.put("instituicao", "Banco Y");
        corpo.put("valorBem", "17430.00");
        corpo.put("entrada", "0.00");
        corpo.put("parcela", "860.69");
        corpo.put("prazo", 36);
        corpo.put("valorFinanciado", "17430.00");
        corpo.put("taxaMensal", "0.03513493");
        corpo.put("taxaAnual", "0.51343428");
        corpo.put("totalPago", "30984.84");
        corpo.put("totalJuros", "13554.84");
        corpo.put("origem", "PROPOSTA");
        corpo.put("ancoraId", null);
        corpo.put("observacao", null);
        corpo.put("calculadaEm", "2026-09-12T15:42:10-03:00");
        return corpo;
    }

    /** Estimativa do slider: R$ 3.000 de entrada, segurando a taxa da proposta âncora. */
    static Map<String, Object> estimativa(UUID ancora) {
        var corpo = proposta();
        corpo.put("entrada", "3000.00");
        corpo.put("valorFinanciado", "14430.00");
        corpo.put("parcela", "712.55");
        corpo.put("totalPago", "25651.80");
        corpo.put("totalJuros", "11221.80");
        corpo.put("origem", "ESTIMATIVA");
        corpo.put("ancoraId", ancora == null ? null : ancora.toString());
        return corpo;
    }

    ResultActions salvar(UUID id, Map<String, Object> corpo) throws Exception {
        return mvc.perform(put(BASE + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    long quantidade() {
        return jdbc.sql("SELECT count(*) FROM calculadora.simulacoes").query(Long.class).single();
    }

    // ---- gravação ----

    @Test
    void grava_proposta_e_ela_aparece_na_lista() throws Exception {
        var id = UUID.randomUUID();

        salvar(id, proposta())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.parcela").value("860.69"))
                .andExpect(jsonPath("$.taxaMensal").value("0.03513493"))
                .andExpect(jsonPath("$.taxaAnual").value("0.51343428"))
                .andExpect(jsonPath("$.status").value("RECEBIDA"))
                .andExpect(jsonPath("$.sincronizadaEm").exists());

        mvc.perform(get(LISTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].totalJuros").value("13554.84"));
    }

    @Test
    void calculada_em_vem_do_cliente_e_created_at_do_servidor() throws Exception {
        var id = UUID.randomUUID();
        salvar(id, proposta()).andExpect(status().isOk());

        var linha = jdbc.sql("""
                        SELECT calculada_em = TIMESTAMPTZ '2026-09-12T15:42:10-03:00' AS preservada,
                               created_at > now() - INTERVAL '1 minute' AS recente
                        FROM calculadora.simulacoes WHERE id = :id""")
                .param("id", id).query().singleRow();
        assertThat(linha).containsEntry("preservada", true).containsEntry("recente", true);
    }

    // ---- idempotência (D2) ----

    @Test
    void mesmo_put_duas_vezes_nao_duplica() throws Exception {
        var id = UUID.randomUUID();
        var primeira = salvar(id, proposta()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var segunda = salvar(id, proposta()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(segunda).isEqualTo(primeira);
        assertThat(quantidade()).isEqualTo(1);
    }

    @Test
    void mesmo_id_com_valores_diferentes_e_conflito() throws Exception {
        var id = UUID.randomUUID();
        salvar(id, proposta()).andExpect(status().isOk());

        var outra = proposta();
        outra.put("instituicao", "Banco Z");
        salvar(id, outra)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("SIMULACAO_DIVERGENTE"));
        assertThat(quantidade()).isEqualTo(1);
    }

    @Test
    void mesmo_id_mudando_so_a_observacao_devolve_o_original() throws Exception {
        var id = UUID.randomUUID();
        salvar(id, proposta()).andExpect(status().isOk());

        var anotada = proposta();
        anotada.put("observacao", "vendedor ofereceu brinde");
        salvar(id, anotada)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observacao").doesNotExist());
    }

    @Test
    void puts_simultaneos_com_o_mesmo_id_gravam_um_registro() throws Exception {
        var id = UUID.randomUUID();
        Callable<Integer> tarefa = () -> salvar(id, proposta()).andReturn().getResponse().getStatus();

        try (var executor = Executors.newFixedThreadPool(4)) {
            var respostas = executor.invokeAll(java.util.List.of(tarefa, tarefa, tarefa, tarefa));
            for (var resposta : respostas) {
                assertThat(resposta.get()).isEqualTo(200);
            }
        }
        assertThat(quantidade()).isEqualTo(1);
    }

    // ---- conferência do snapshot (D6) ----

    @Test
    void taxa_fora_da_tolerancia_e_rejeitada() throws Exception {
        var corpo = proposta();
        corpo.put("taxaMensal", "0.03520000");
        salvar(UUID.randomUUID(), corpo)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("SNAPSHOT_DIVERGENTE"))
                .andExpect(jsonPath("$.detalhes.campos.taxaMensal.enviado").value("0.03520000"))
                .andExpect(jsonPath("$.detalhes.campos.taxaMensal.esperado").value("0.03513493"));
        assertThat(quantidade()).isZero();
    }

    @Test
    void total_de_juros_errado_em_um_centavo_e_rejeitado() throws Exception {
        var corpo = proposta();
        corpo.put("totalJuros", "13554.85");
        salvar(UUID.randomUUID(), corpo)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("SNAPSHOT_DIVERGENTE"))
                .andExpect(jsonPath("$.detalhes.campos.totalJuros.enviado").value("13554.85"))
                .andExpect(jsonPath("$.detalhes.campos.totalJuros.esperado").value("13554.84"));
    }

    @Test
    void valor_financiado_incoerente_e_rejeitado() throws Exception {
        var corpo = proposta();
        corpo.put("valorFinanciado", "17000.00");
        salvar(UUID.randomUUID(), corpo)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.valorFinanciado.enviado").value("17000.00"))
                .andExpect(jsonPath("$.detalhes.campos.valorFinanciado.esperado").value("17430.00"));
    }

    @Test
    void taxa_anual_incoerente_e_rejeitada() throws Exception {
        var corpo = proposta();
        corpo.put("taxaAnual", "0.51400000");
        salvar(UUID.randomUUID(), corpo)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.taxaAnual.enviado").value("0.51400000"))
                .andExpect(jsonPath("$.detalhes.campos.taxaAnual.esperado").value("0.51343428"));
    }

    @Test
    void proposta_sem_juros_e_rejeitada_com_o_erro_do_core() throws Exception {
        var corpo = proposta();
        corpo.put("parcela", "100.00");
        corpo.put("prazo", 12);
        corpo.put("valorBem", "1200.00");
        corpo.put("valorFinanciado", "1200.00");
        corpo.put("totalPago", "1200.00");
        corpo.put("totalJuros", "0.00");
        salvar(UUID.randomUUID(), corpo)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("SEM_JUROS"));
    }

    // ---- estimativas (RN11) ----

    @Test
    void estimativa_sem_ancora_e_rejeitada() throws Exception {
        salvar(UUID.randomUUID(), estimativa(null))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("ANCORA_INVALIDA"));
    }

    @Test
    void estimativa_com_ancora_inexistente_e_rejeitada() throws Exception {
        salvar(UUID.randomUUID(), estimativa(UUID.randomUUID()))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("ANCORA_INVALIDA"));
    }

    @Test
    void estimativa_ancorada_em_outra_estimativa_e_rejeitada() throws Exception {
        var ancora = UUID.randomUUID();
        salvar(ancora, proposta()).andExpect(status().isOk());
        var primeira = UUID.randomUUID();
        salvar(primeira, estimativa(ancora)).andExpect(status().isOk());

        salvar(UUID.randomUUID(), estimativa(primeira))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("ANCORA_INVALIDA"));
    }

    @Test
    void estimativa_com_ancora_valida_e_gravada() throws Exception {
        var ancora = UUID.randomUUID();
        salvar(ancora, proposta()).andExpect(status().isOk());

        salvar(UUID.randomUUID(), estimativa(ancora))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origem").value("ESTIMATIVA"))
                .andExpect(jsonPath("$.ancoraId").value(ancora.toString()));
    }

    @Test
    void estimativa_com_taxa_diferente_da_ancora_e_rejeitada() throws Exception {
        var ancora = UUID.randomUUID();
        salvar(ancora, proposta()).andExpect(status().isOk());

        var corpo = estimativa(ancora);
        corpo.put("taxaMensal", "0.03000000");
        salvar(UUID.randomUUID(), corpo)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("SNAPSHOT_DIVERGENTE"));
    }

    @Test
    void banco_rejeita_estimativa_sem_ancora_inserida_direto() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO calculadora.simulacoes
                    (id, bem, data, valor_bem, entrada, parcela, prazo, valor_financiado, taxa_mensal, taxa_anual,
                     total_pago, total_juros, origem, calculada_em)
                VALUES (gen_random_uuid(), 'X', DATE '2026-09-12', 1000, 0, 100, 12, 1000, 0.01, 0.12,
                        1200, 200, 'ESTIMATIVA', now())""").update())
                .hasMessageContaining("estimativa_exige_ancora");
    }

    // ---- imutabilidade no banco (RN09) ----

    @Test
    void banco_impede_alterar_valores_mas_permite_anotar() throws Exception {
        var id = UUID.randomUUID();
        salvar(id, proposta()).andExpect(status().isOk());

        assertThatThrownBy(() -> jdbc.sql("UPDATE calculadora.simulacoes SET parcela = 1 WHERE id = :id")
                .param("id", id).update())
                .hasMessageContaining("imutáveis");

        var alteradas = jdbc.sql("UPDATE calculadora.simulacoes SET observacao = 'ok', status = 'ACEITA' WHERE id = :id")
                .param("id", id).update();
        assertThat(alteradas).isEqualTo(1);
    }

    // ---- validação (RN10) ----

    @Test
    void entrada_igual_ao_valor_do_bem_e_invalida() throws Exception {
        var corpo = proposta();
        corpo.put("entrada", "17430.00");
        salvar(UUID.randomUUID(), corpo)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalhes.campos.entrada").value("deve ser menor que o valor do bem"));
    }

    @Test
    void prazo_zero_e_invalido() throws Exception {
        var corpo = proposta();
        corpo.put("prazo", 0);
        salvar(UUID.randomUUID(), corpo)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalhes.campos.prazo").value("deve ser maior que zero"));
    }

    @Test
    void parcela_negativa_e_invalida() throws Exception {
        var corpo = proposta();
        corpo.put("parcela", "-860.69");
        salvar(UUID.randomUUID(), corpo)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalhes.campos.parcela").value("deve ser maior que zero"));
    }

    @Test
    void campos_obrigatorios_ausentes_sao_invalidos() throws Exception {
        var corpo = proposta();
        corpo.remove("bem");
        corpo.remove("calculadaEm");
        salvar(UUID.randomUUID(), corpo)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.bem").value("deve ser texto não vazio"))
                .andExpect(jsonPath("$.detalhes.campos.calculadaEm").value("é obrigatório"));
    }

    @Test
    void id_que_nao_e_uuid_e_rejeitado() throws Exception {
        mvc.perform(put(BASE + "abc").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(proposta())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("PARAMETRO_INVALIDO"));
    }

    // ---- listagem (RF21) ----

    @Test
    void filtro_por_bem_ignora_maiusculas_e_espacos() throws Exception {
        salvar(UUID.randomUUID(), proposta()).andExpect(status().isOk());
        var outra = proposta();
        outra.put("bem", "Yamaha Fazer 250");
        salvar(UUID.randomUUID(), outra).andExpect(status().isOk());

        mvc.perform(get(LISTA).param("bem", "  honda cb 300f "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].bem").value("Honda CB 300F"));
    }

    @Test
    void lista_em_ordem_de_data_decrescente() throws Exception {
        var antiga = proposta();
        antiga.put("data", "2026-08-01");
        salvar(UUID.randomUUID(), antiga).andExpect(status().isOk());
        salvar(UUID.randomUUID(), proposta()).andExpect(status().isOk());

        mvc.perform(get(LISTA))
                .andExpect(jsonPath("$[0].data").value("2026-09-12"))
                .andExpect(jsonPath("$[1].data").value("2026-08-01"));
    }
}
