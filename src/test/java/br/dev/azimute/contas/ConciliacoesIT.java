package br.dev.azimute.contas;

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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Conciliação contra o Postgres real: divergência gravada, nova base do saldo e imutabilidade.
 * Refs: RF20, RF21, RN06, RN07, RN08, P3, T4.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ConciliacoesIT {

    private static final String CONTAS = "/api/contas/";

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    MockMvc mvc;

    UUID nubank;
    UUID cdb;

    @BeforeEach
    void preparar() throws Exception {
        mvc = ClienteDeTeste.autenticado(contexto, jdbc);
        jdbc.sql("TRUNCATE contas.conciliacoes, contas.movimentacoes, contas.contas CASCADE").update();
        nubank = criarConta("Nubank", "CORRENTE", "GIRO");
        cdb = criarConta("CDB", "INVESTIMENTO", "RESERVA");
        // Nubank: 1000 em 01/09; sai 200 em 05/09 → calculado 800 a partir de 05/09.
        transferir(UUID.randomUUID(), nubank, cdb, "200", "2026-09-05");
    }

    @Test
    void conciliar_registra_a_divergencia_e_o_saldo_parte_do_valor_real() throws Exception {
        conciliar(nubank, "2026-09-10", "770", "pix esquecido").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").value("2026-09-10"))
                .andExpect(jsonPath("$.saldoReal").value("770.00"))
                .andExpect(jsonPath("$.saldoCalculado").value("800.00"))
                .andExpect(jsonPath("$.divergencia").value("-30.00"))
                .andExpect(jsonPath("$.justificativa").value("pix esquecido"));

        transferir(UUID.randomUUID(), nubank, cdb, "70", "2026-09-12");
        saldo(nubank).andExpect(jsonPath("$.atual").value("700.00"))
                .andExpect(jsonPath("$.ultimaConciliacao.data").value("2026-09-10"))
                .andExpect(jsonPath("$.movimentacoesDesdeABase").value(1));
    }

    @Test
    void excluir_ou_lancar_movimentacao_antiga_nao_muda_a_divergencia_registrada() throws Exception {
        var antiga = UUID.randomUUID();
        transferir(antiga, nubank, cdb, "50", "2026-09-06");
        conciliar(nubank, "2026-09-10", "700", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.divergencia").value("-50.00"));

        mvc.perform(delete(CONTAS + "movimentacoes/" + antiga)).andExpect(status().isNoContent());
        transferir(UUID.randomUUID(), cdb, nubank, "25", "2026-09-07");
        mvc.perform(post(CONTAS + "movimentacoes/" + antiga + "/restaurar")).andExpect(status().isOk());

        historico(nubank).andExpect(jsonPath("$[0].data").value("2026-09-10"))
                .andExpect(jsonPath("$[0].saldoCalculado").value("750.00"))
                .andExpect(jsonPath("$[0].divergencia").value("-50.00"));
        saldo(nubank).andExpect(jsonPath("$.atual").value("700.00"));
    }

    @Test
    void historico_vem_do_mais_recente_para_o_mais_antigo_com_a_inicial() throws Exception {
        conciliar(nubank, "2026-09-10", "800", null).andExpect(status().isCreated());
        conciliar(nubank, "2026-09-20", "790", "tarifa").andExpect(status().isCreated());

        historico(nubank).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].data").value("2026-09-20"))
                .andExpect(jsonPath("$[0].divergencia").value("-10.00"))
                .andExpect(jsonPath("$[1].data").value("2026-09-10"))
                .andExpect(jsonPath("$[1].divergencia").value("0.00"))
                .andExpect(jsonPath("$[2].data").value("2026-09-01"))
                .andExpect(jsonPath("$[2].saldoReal").value("1000.00"));
    }

    @Test
    void reenvio_identico_e_200_e_data_igual_com_outro_valor_e_422() throws Exception {
        conciliar(nubank, "2026-09-10", "770", null).andExpect(status().isCreated());

        conciliar(nubank, "2026-09-10", "770", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.divergencia").value("-30.00"));
        conciliar(nubank, "2026-09-10", "760", null).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("CONCILIACAO_FORA_DE_ORDEM"));
        conciliar(nubank, "2026-09-09", "760", null).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.ultimaConciliacao").value("2026-09-10"));
        historico(nubank).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void data_futura_conta_inativa_e_valor_invalido() throws Exception {
        var amanha = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(1).toString();
        conciliar(nubank, amanha, "1", null).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("DATA_FUTURA"));

        conciliar(nubank, "2026-09-10", "1.234", null).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.saldoReal").exists());
        var semData = new LinkedHashMap<String, Object>(Map.of("saldoReal", "1"));
        mvc.perform(post(CONTAS + nubank + "/conciliacoes").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(semData)))
                .andExpect(status().is(422)).andExpect(jsonPath("$.detalhes.campos.data").exists());

        mvc.perform(patch(CONTAS + cdb).contentType(MediaType.APPLICATION_JSON).content("{\"ativa\": false}"))
                .andExpect(status().isOk());
        conciliar(cdb, "2026-09-10", "9000", null).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("CONTA_INATIVA"));
    }

    @Test
    void saldo_real_negativo_e_aceito() throws Exception {
        conciliar(nubank, "2026-09-10", "-150", "cheque especial").andExpect(status().isCreated())
                .andExpect(jsonPath("$.divergencia").value("-950.00"));

        saldo(nubank).andExpect(jsonPath("$.atual").value("-150.00"));
    }

    @Test
    void conta_inexistente_e_404() throws Exception {
        conciliar(UUID.randomUUID(), "2026-09-10", "1", null).andExpect(status().isNotFound());
        historico(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void banco_recusa_alterar_a_conciliacao() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE contas.conciliacoes SET divergencia = 0").update())
                .hasMessageContaining("Registro imutável em conciliacoes");
    }

    @Test
    void sem_token_e_401() throws Exception {
        var anonimo = ClienteDeTeste.anonimo(contexto);
        anonimo.perform(get(CONTAS + nubank + "/conciliacoes")).andExpect(status().isUnauthorized());
        anonimo.perform(post(CONTAS + nubank + "/conciliacoes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\": \"2026-09-10\", \"saldoReal\": \"1\"}")).andExpect(status().isUnauthorized());
    }

    private UUID criarConta(String apelido, String tipo, String funcao) throws Exception {
        var corpo = Map.of("apelido", apelido, "instituicao", "Banco", "tipo", tipo, "funcao", funcao,
                "saldoInicial", "1000", "dataSaldoInicial", "2026-09-01");
        var resposta = mvc.perform(post("/api/contas").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(corpo)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resposta).get("id").asString());
    }

    private void transferir(UUID id, UUID origem, UUID destino, String valor, String data) throws Exception {
        var corpo = Map.of("tipo", "TRANSFERENCIA", "origem", origem.toString(), "destino", destino.toString(),
                "valor", valor, "data", data);
        mvc.perform(put(CONTAS + "movimentacoes/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo))).andExpect(status().isOk());
    }

    private ResultActions conciliar(UUID conta, String data, String saldoReal, String justificativa)
            throws Exception {
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("data", data);
        corpo.put("saldoReal", saldoReal);
        if (justificativa != null) {
            corpo.put("justificativa", justificativa);
        }
        return mvc.perform(post(CONTAS + conta + "/conciliacoes").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    private ResultActions historico(UUID conta) throws Exception {
        return mvc.perform(get(CONTAS + conta + "/conciliacoes"));
    }

    private ResultActions saldo(UUID conta) throws Exception {
        return mvc.perform(get(CONTAS + conta + "/saldo")).andExpect(status().isOk());
    }
}
