package br.dev.azimute.fluxo;

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
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Resumo do mês pela API (RF50, RN17, T14): à vista e em folha, média dos 3 meses anteriores, recebido e sobra. */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ResumoIT {

    private static final String RESUMO = "/api/fluxo/resumo";

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    MockMvc mvc;

    UUID conta;
    UUID lazer;
    UUID transporte;

    @BeforeEach
    void preparar() throws Exception {
        mvc = ClienteDeTeste.autenticado(contexto, jdbc);
        jdbc.sql("TRUNCATE contas.conciliacoes, contas.movimentacoes, contas.contas, fluxo.holerites, fluxo.ganhos "
                + "CASCADE").update();
        conta = new MontadorDeFluxo(jdbc).conta();
        lazer = criarCategoria("Cinema " + UUID.randomUUID());
        transporte = criarCategoria("Ônibus " + UUID.randomUUID());
    }

    @Test
    void resumo_soma_a_vista_e_em_folha_compara_com_a_media_e_calcula_a_sobra() throws Exception {
        gasto(lazer, "90", "2026-07-10");
        gasto(lazer, "60", "2026-08-20");
        gasto(lazer, "30", "2026-10-05");
        gasto(lazer, "999", "2026-06-30");
        var excluido = gasto(lazer, "500", "2026-10-06");
        mvc.perform(delete("/api/gastos/" + excluido)).andExpect(status().isNoContent());
        gastoEmFolha(transporte, "300", "2026-10-30");
        ganho("2026-10", "1000", "2026-11-03");
        ganho("2026-09", "777", "2026-10-03");

        mvc.perform(get(RESUMO).param("competencia", "2026-10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.competencia").value("2026-10"))
                .andExpect(jsonPath("$.totalGasto").value("330.00"))
                .andExpect(jsonPath("$.recebido").value("1000.00"))
                .andExpect(jsonPath("$.sobra").value("670.00"))
                .andExpect(jsonPath("$.categorias", hasSize(2)))
                .andExpect(jsonPath("$.categorias[0].categoria").value(transporte.toString()))
                .andExpect(jsonPath("$.categorias[0].total").value("300.00"))
                .andExpect(jsonPath("$.categorias[0].mediaTresMeses").value("0.00"))
                .andExpect(jsonPath("$.categorias[1].categoria").value(lazer.toString()))
                .andExpect(jsonPath("$.categorias[1].total").value("30.00"))
                .andExpect(jsonPath("$.categorias[1].mediaTresMeses").value("50.00"));
    }

    @Test
    void competencia_vazia_e_tudo_zero_e_competencia_invalida_e_422() throws Exception {
        mvc.perform(get(RESUMO).param("competencia", "2030-01")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalGasto").value("0.00"))
                .andExpect(jsonPath("$.recebido").value("0.00"))
                .andExpect(jsonPath("$.sobra").value("0.00"))
                .andExpect(jsonPath("$.categorias", hasSize(0)));
        mvc.perform(get(RESUMO).param("competencia", "2026-13")).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("COMPETENCIA_INVALIDA"));
        ClienteDeTeste.anonimo(contexto).perform(get(RESUMO).param("competencia", "2026-10"))
                .andExpect(status().isUnauthorized());
    }

    private UUID criarCategoria(String nome) throws Exception {
        var resposta = mvc.perform(post("/api/categorias").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nome", nome))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resposta).get("id").asString());
    }

    private UUID gasto(UUID categoria, String valor, String data) throws Exception {
        var id = UUID.randomUUID();
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("data", data);
        corpo.put("descricao", "Gasto");
        corpo.put("valor", valor);
        corpo.put("categoria", categoria.toString());
        corpo.put("conta", conta.toString());
        mvc.perform(put("/api/gastos/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo))).andExpect(status().isCreated());
        return id;
    }

    private void gastoEmFolha(UUID categoria, String valor, String data) {
        var holerite = new MontadorDeFluxo(jdbc).holerite();
        jdbc.sql("""
                        INSERT INTO fluxo.gastos (id, data, descricao, valor, categoria_id, forma, holerite_id)
                        VALUES (:id, CAST(:data AS DATE), 'Vale-Transporte', CAST(:valor AS NUMERIC), :cat,
                                'DESCONTO_EM_FOLHA', :h)""")
                .param("id", UUID.randomUUID()).param("data", data).param("valor", valor).param("cat", categoria)
                .param("h", holerite).update();
    }

    private void ganho(String competencia, String liquido, String recebidoEm) throws Exception {
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("competencia", competencia);
        corpo.put("descricao", "Freela");
        corpo.put("tipo", "FREELA");
        corpo.put("bruto", liquido);
        corpo.put("liquido", liquido);
        corpo.put("conta", conta.toString());
        corpo.put("recebidoEm", recebidoEm);
        mvc.perform(put("/api/ganhos/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo))).andExpect(status().isCreated());
    }
}
