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
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exclusão lógica e restauração de simulação, e as datas de controle (RF40, RF41, RN14 do PRD de contas).
 * A API continua com os mesmos campos: {@code sincronizadaEm} agora vem de {@code created_at}.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class HistoricoExclusaoIT {

    private static final String LISTA = "/api/calculadora/historico";
    private static final String BASE = LISTA + "/";
    private static final String MOTO = "Honda CB 300F";

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    MockMvc mvc;

    @BeforeEach
    void limpar() {
        mvc = ClienteDeTeste.autenticado(contexto, jdbc);
        jdbc.sql("TRUNCATE calculadora.simulacoes").update();
    }

    @Test
    void excluida_some_da_listagem_e_da_comparacao_mas_fica_no_banco() throws Exception {
        var id = salvar(UUID.randomUUID(), HistoricoGravacaoIT.proposta());

        mvc.perform(delete(BASE + id)).andExpect(status().isNoContent());

        mvc.perform(get(LISTA)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get(LISTA + "/comparar").param("bem", MOTO)).andExpect(jsonPath("$.propostas", hasSize(0)));
        var excluida = jdbc.sql("SELECT deleted_at IS NOT NULL FROM calculadora.simulacoes WHERE id = :id")
                .param("id", id).query(Boolean.class).single();
        assertThat(excluida).isTrue();
    }

    @Test
    void restaurar_devolve_a_listagem_e_a_comparacao() throws Exception {
        var id = salvar(UUID.randomUUID(), HistoricoGravacaoIT.proposta());
        mvc.perform(delete(BASE + id)).andExpect(status().isNoContent());

        mvc.perform(post(BASE + id + "/restaurar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.sincronizadaEm").exists());
        mvc.perform(post(BASE + id + "/restaurar")).andExpect(status().isOk());

        mvc.perform(get(LISTA)).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get(LISTA + "/comparar").param("bem", MOTO)).andExpect(jsonPath("$.propostas", hasSize(1)));
    }

    @Test
    void restaurar_inexistente_e_404() throws Exception {
        mvc.perform(post(BASE + UUID.randomUUID() + "/restaurar")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("SIMULACAO_NAO_ENCONTRADA"));
    }

    @Test
    void ancora_so_e_protegida_pelas_estimativas_vivas() throws Exception {
        var ancora = salvar(UUID.randomUUID(), HistoricoGravacaoIT.proposta());
        var estimativa = salvar(UUID.randomUUID(), HistoricoGravacaoIT.estimativa(ancora));

        mvc.perform(delete(BASE + ancora)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("SIMULACAO_E_ANCORA"));

        mvc.perform(delete(BASE + estimativa)).andExpect(status().isNoContent());
        mvc.perform(delete(BASE + ancora)).andExpect(status().isNoContent());
    }

    @Test
    void restaurar_estimativa_com_ancora_excluida_restaura_a_ancora_junto() throws Exception {
        var ancora = salvar(UUID.randomUUID(), HistoricoGravacaoIT.proposta());
        var estimativa = salvar(UUID.randomUUID(), HistoricoGravacaoIT.estimativa(ancora));
        mvc.perform(delete(BASE + estimativa)).andExpect(status().isNoContent());
        mvc.perform(delete(BASE + ancora)).andExpect(status().isNoContent());

        mvc.perform(post(BASE + estimativa + "/restaurar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(estimativa.toString()))
                .andExpect(jsonPath("$.ancoraId").value(ancora.toString()));

        mvc.perform(get(LISTA)).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(delete(BASE + ancora)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("SIMULACAO_E_ANCORA"));
    }

    @Test
    void restaurar_a_proposta_nao_traz_as_estimativas_excluidas() throws Exception {
        var ancora = salvar(UUID.randomUUID(), HistoricoGravacaoIT.proposta());
        var estimativa = salvar(UUID.randomUUID(), HistoricoGravacaoIT.estimativa(ancora));
        mvc.perform(delete(BASE + estimativa)).andExpect(status().isNoContent());
        mvc.perform(delete(BASE + ancora)).andExpect(status().isNoContent());

        mvc.perform(post(BASE + ancora + "/restaurar")).andExpect(status().isOk());

        mvc.perform(get(LISTA)).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(ancora.toString()));
    }

    @Test
    void estimativa_nova_com_ancora_excluida_e_recusada() throws Exception {
        var ancora = salvar(UUID.randomUUID(), HistoricoGravacaoIT.proposta());
        mvc.perform(delete(BASE + ancora)).andExpect(status().isNoContent());

        enviar(UUID.randomUUID(), HistoricoGravacaoIT.estimativa(ancora)).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("ANCORA_INVALIDA"));
    }

    @Test
    void reenvio_de_simulacao_excluida_e_409() throws Exception {
        var id = salvar(UUID.randomUUID(), HistoricoGravacaoIT.proposta());
        mvc.perform(delete(BASE + id)).andExpect(status().isNoContent());

        enviar(id, HistoricoGravacaoIT.proposta()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("SIMULACAO_EXCLUIDA"));
    }

    @Test
    void anotar_simulacao_excluida_e_404() throws Exception {
        var id = salvar(UUID.randomUUID(), HistoricoGravacaoIT.proposta());
        mvc.perform(delete(BASE + id)).andExpect(status().isNoContent());

        mvc.perform(patch(BASE + id).contentType(MediaType.APPLICATION_JSON).content("{\"observacao\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotar_muda_updated_at_e_nao_created_at() throws Exception {
        var id = salvar(UUID.randomUUID(), HistoricoGravacaoIT.proposta());
        var antes = datas(id);

        mvc.perform(patch(BASE + id).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACEITA\"}"))
                .andExpect(status().isOk());

        var depois = datas(id);
        assertThat(depois.get("created_at")).isEqualTo(antes.get("created_at"));
        assertThat(depois.get("updated_at")).isAfter(antes.get("updated_at"));
    }

    @Test
    void trigger_de_valores_deixa_mudar_as_datas_de_controle() throws Exception {
        var id = salvar(UUID.randomUUID(), HistoricoGravacaoIT.proposta());

        var linhas = jdbc.sql("UPDATE calculadora.simulacoes SET deleted_at = now(), updated_at = now() WHERE id = :id")
                .param("id", id).update();

        assertThat(linhas).isEqualTo(1);
    }

    @Test
    void colunas_seguem_o_padrao_de_datas() {
        var colunas = jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'calculadora' AND table_name = 'simulacoes'""")
                .query(String.class).list();

        assertThat(colunas).contains("created_at", "updated_at", "deleted_at").doesNotContain("sincronizada_em");
    }

    private UUID salvar(UUID id, Map<String, Object> corpo) throws Exception {
        enviar(id, corpo).andExpect(status().isOk());
        return id;
    }

    private ResultActions enviar(UUID id, Map<String, Object> corpo) throws Exception {
        return mvc.perform(put(BASE + id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo)));
    }

    private Map<String, OffsetDateTime> datas(UUID id) {
        return jdbc.sql("SELECT created_at, updated_at FROM calculadora.simulacoes WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> Map.of("created_at", rs.getObject("created_at", OffsetDateTime.class),
                        "updated_at", rs.getObject("updated_at", OffsetDateTime.class)))
                .single();
    }
}
