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
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A visão central do módulo pela API (RF06, RN01, RN02, RN12, RN13). Sem o módulo {@code cartoes}, não há fonte
 * de dívida: as dívidas são zero.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ConsolidadoIT {

    private static final String CONSOLIDADO = "/api/contas/consolidado";

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    MockMvc mvc;

    UUID nubank;
    UUID cdb;

    @BeforeEach
    void preparar() throws Exception {
        mvc = ClienteDeTeste.autenticado(contexto, jdbc);
        jdbc.sql("TRUNCATE contas.conciliacoes, contas.movimentacoes, contas.contas").update();
        nubank = criar("Nubank", "CORRENTE", "GIRO", "2300", "1000");
        cdb = criar("CDB Liquidez", "INVESTIMENTO", "RESERVA", "9000", null);
    }

    @Test
    void consolidado_traz_posicao_geral_saldo_em_conta_e_as_contas() throws Exception {
        mvc.perform(get(CONSOLIDADO)).andExpect(status().isOk())
                .andExpect(jsonPath("$.posicaoGeral").value("11300.00"))
                .andExpect(jsonPath("$.dividas").value("0.00"))
                .andExpect(jsonPath("$.saldoEmConta").value("2300.00"))
                .andExpect(jsonPath("$.contas", hasSize(2)))
                .andExpect(jsonPath("$.contas[0].apelido").value("CDB Liquidez"))
                .andExpect(jsonPath("$.contas[1].saldo.chequeEspecial.limite").value("1000.00"));
    }

    @Test
    void transferencia_para_a_reserva_mantem_a_posicao_e_reduz_o_saldo_em_conta() throws Exception {
        var corpo = Map.of("tipo", "TRANSFERENCIA", "origem", nubank.toString(), "destino", cdb.toString(),
                "valor", "1500", "data", "2026-11-05");
        mvc.perform(put("/api/contas/movimentacoes/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo))).andExpect(status().isOk());

        mvc.perform(get(CONSOLIDADO))
                .andExpect(jsonPath("$.posicaoGeral").value("11300.00"))
                .andExpect(jsonPath("$.saldoEmConta").value("800.00"));
    }

    @Test
    void corrente_negativa_entra_negativa_na_posicao_e_zero_no_saldo_em_conta() throws Exception {
        var corpo = Map.of("tipo", "TRANSFERENCIA", "origem", nubank.toString(), "destino", cdb.toString(),
                "valor", "2600", "data", "2026-11-05");
        mvc.perform(put("/api/contas/movimentacoes/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo))).andExpect(status().isOk());

        mvc.perform(get(CONSOLIDADO))
                .andExpect(jsonPath("$.posicaoGeral").value("11300.00"))
                .andExpect(jsonPath("$.saldoEmConta").value("0.00"))
                .andExpect(jsonPath("$.contas[1].saldo.atual").value("-300.00"))
                .andExpect(jsonPath("$.contas[1].saldo.chequeEspecial.emUso").value("300.00"));
    }

    @Test
    void conta_inativa_sai_do_consolidado() throws Exception {
        mvc.perform(patch("/api/contas/" + cdb).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ativa\": false}")).andExpect(status().isOk());

        mvc.perform(get(CONSOLIDADO))
                .andExpect(jsonPath("$.posicaoGeral").value("2300.00"))
                .andExpect(jsonPath("$.contas", hasSize(1)));
    }

    @Test
    void fgts_e_previdencia_aparecem_so_no_bloqueado_e_a_inativa_some() throws Exception {
        criar("FGTS", "FGTS", "RESERVA", "1000", null);
        var previdencia = criar("Previdência", "PREVIDENCIA", "RESERVA", "250", null);
        criar("Previdência antiga", "PREVIDENCIA", "RESERVA", "80", null);
        mvc.perform(patch("/api/contas/" + previdencia).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ativa\": false}")).andExpect(status().isOk());

        mvc.perform(get(CONSOLIDADO)).andExpect(status().isOk())
                .andExpect(jsonPath("$.posicaoGeral").value("11300.00"))
                .andExpect(jsonPath("$.saldoEmConta").value("2300.00"))
                .andExpect(jsonPath("$.bloqueado").value("1080.00"))
                .andExpect(jsonPath("$.contas", hasSize(4)));
    }

    @Test
    void sem_conta_bloqueada_o_bloqueado_vem_zero() throws Exception {
        mvc.perform(get(CONSOLIDADO)).andExpect(jsonPath("$.bloqueado").value("0.00"));
    }

    @Test
    void limite_de_cheque_especial_em_fgts_e_recusado() throws Exception {
        var corpo = Map.of("apelido", "FGTS", "instituicao", "Caixa", "tipo", "FGTS", "funcao", "RESERVA",
                "saldoInicial", "10", "dataSaldoInicial", "2026-11-01", "limiteChequeEspecial", "100");
        mvc.perform(post("/api/contas").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo))).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("LIMITE_SO_EM_CONTA_CORRENTE"));
    }

    @Test
    void sem_token_e_401() throws Exception {
        ClienteDeTeste.anonimo(contexto).perform(get(CONSOLIDADO)).andExpect(status().isUnauthorized());
    }

    private UUID criar(String apelido, String tipo, String funcao, String saldo, String limite) throws Exception {
        var corpo = new java.util.LinkedHashMap<String, Object>(Map.of("apelido", apelido, "instituicao", "Banco",
                "tipo", tipo, "funcao", funcao, "saldoInicial", saldo, "dataSaldoInicial", "2026-11-01"));
        if (limite != null) {
            corpo.put("limiteChequeEspecial", limite);
        }
        var resposta = mvc.perform(post("/api/contas").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(corpo)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resposta).get("id").asString());
    }
}
