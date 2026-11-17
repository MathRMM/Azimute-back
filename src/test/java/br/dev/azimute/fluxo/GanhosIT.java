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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ganhos avulsos pela API, com a entrada na conta (RF20–RF24, RN02, RN04, RN09, P4). */
@SpringBootTest
@Import(TestcontainersConfig.class)
class GanhosIT {

    private static final String GANHOS = "/api/ganhos";

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    MockMvc mvc;

    UUID nubank;
    UUID inter;

    @BeforeEach
    void preparar() throws Exception {
        mvc = ClienteDeTeste.autenticado(contexto, jdbc);
        // CASCADE leva junto os recebimentos, que apontam para as contas e para os ganhos.
        jdbc.sql("TRUNCATE contas.conciliacoes, contas.movimentacoes, contas.contas, fluxo.ganhos CASCADE")
                .update();
        nubank = criarConta("Nubank");
        inter = criarConta("Inter");
    }

    @Test
    void put_cria_com_201_e_o_saldo_sobe_reenvio_e_200_e_corpo_diferente_e_409() throws Exception {
        var id = UUID.randomUUID();

        registrar(id, ganho("FREELA", "1000", "850", nubank, "2026-09-10")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.competencia").value("2026-09"))
                .andExpect(jsonPath("$.descricao").value("Projeto"))
                .andExpect(jsonPath("$.tipo").value("FREELA"))
                .andExpect(jsonPath("$.bruto").value("1000.00"))
                .andExpect(jsonPath("$.liquido").value("850.00"))
                .andExpect(jsonPath("$.holerite").doesNotExist())
                .andExpect(jsonPath("$.recebimentos", hasSize(1)))
                .andExpect(jsonPath("$.recebimentos[0].id").value(id.toString()))
                .andExpect(jsonPath("$.recebimentos[0].papel").value("AVULSO"))
                .andExpect(jsonPath("$.recebimentos[0].valor").value("850.00"))
                .andExpect(jsonPath("$.recebimentos[0].data").value("2026-09-10"))
                .andExpect(jsonPath("$.recebimentos[0].conta").value(nubank.toString()));
        saldo(nubank, "3850.00");

        registrar(id, ganho("FREELA", "1000", "850", nubank, "2026-09-10")).andExpect(status().isOk());
        registrar(id, ganho("FREELA", "1000", "800", nubank, "2026-09-10")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("GANHO_DIVERGENTE"));
        saldo(nubank, "3850.00");
    }

    @Test
    void regras_do_registro() throws Exception {
        registrar(UUID.randomUUID(), ganho("SALARIO", "5000", "3800", nubank, "2026-09-30"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.erro").value("SALARIO_SO_PELO_HOLERITE"));
        registrar(UUID.randomUUID(), ganho("FREELA", "100", "100.01", nubank, "2026-09-30"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.erro").value("LIQUIDO_MAIOR_QUE_BRUTO"));
        registrar(UUID.randomUUID(), ganho("BONUS", "100", "100", nubank, "2026-09-30"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.detalhes.campos.tipo")
                        .value("deve ser PLR, FREELA, REEMBOLSO ou OUTRO"));
        var invalido = ganho("FREELA", "1.234", "0", nubank, "2026-09-30");
        invalido.put("competencia", "2026-13");
        registrar(UUID.randomUUID(), invalido).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.bruto")
                        .value("deve ter até 2 casas decimais"))
                .andExpect(jsonPath("$.detalhes.campos.liquido").value("deve ser maior que zero"))
                .andExpect(jsonPath("$.detalhes.campos.competencia").value("deve estar no formato AAAA-MM"));
        registrar(UUID.randomUUID(), Map.of("bruto", "10")).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.descricao").value("deve ser texto não vazio"))
                .andExpect(jsonPath("$.detalhes.campos.conta").value("é obrigatório"))
                .andExpect(jsonPath("$.detalhes.campos.recebidoEm").value("é obrigatório"));
        registrar(UUID.randomUUID(), ganho("FREELA", "100", "100", UUID.randomUUID(), "2026-09-30"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.erro").value("CONTA_NAO_ENCONTRADA"));
        ClienteDeTeste.anonimo(contexto).perform(get(GANHOS).param("competencia", "2026-09"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.sql("SELECT count(*) FROM fluxo.ganhos").query(Long.class).single()).isZero();
    }

    @Test
    void competencia_diferente_do_mes_do_recebimento_e_aceita() throws Exception {
        var corpo = ganho("REEMBOLSO", "300", "300", nubank, "2026-11-03");
        corpo.put("competencia", "2026-10");

        registrar(UUID.randomUUID(), corpo).andExpect(status().isCreated())
                .andExpect(jsonPath("$.competencia").value("2026-10"))
                .andExpect(jsonPath("$.recebimentos[0].data").value("2026-11-03"));
        listar("2026-10").andExpect(jsonPath("$", hasSize(1)));
        listar("2026-11").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void editar_o_liquido_e_a_conta_ajusta_a_entrada() throws Exception {
        var id = UUID.randomUUID();
        registrar(id, ganho("FREELA", "1000", "850", nubank, "2026-09-10")).andExpect(status().isCreated());

        alterar(id, Map.of("liquido", "900", "conta", inter.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.liquido").value("900.00"))
                .andExpect(jsonPath("$.recebimentos[0].valor").value("900.00"))
                .andExpect(jsonPath("$.recebimentos[0].conta").value(inter.toString()));
        saldo(nubank, "3000.00");
        saldo(inter, "3900.00");

        alterar(id, Map.of("descricao", "Projeto grande", "tipo", "OUTRO", "competencia", "2026-08"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tipo").value("OUTRO"))
                .andExpect(jsonPath("$.competencia").value("2026-08"));
        saldo(inter, "3900.00");
    }

    @Test
    void erros_do_patch() throws Exception {
        var id = UUID.randomUUID();
        registrar(id, ganho("FREELA", "1000", "850", nubank, "2026-09-10")).andExpect(status().isCreated());

        alterar(id, Map.of("bruto", "800")).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("LIQUIDO_MAIOR_QUE_BRUTO"));
        alterar(id, Map.of("tipo", "SALARIO")).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("SALARIO_SO_PELO_HOLERITE"));
        alterar(id, Map.of("holerite", "x")).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("CAMPO_NAO_EDITAVEL"));
        alterar(id, Map.of("competencia", "out", "descricao", " ", "tipo", "BONUS", "bruto", "-1", "liquido", "0",
                "conta", "x", "recebidoEm", "ontem"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.competencia").value("deve estar no formato AAAA-MM"))
                .andExpect(jsonPath("$.detalhes.campos.descricao").value("deve ser texto não vazio"))
                .andExpect(jsonPath("$.detalhes.campos.tipo").value("deve ser PLR, FREELA, REEMBOLSO ou OUTRO"))
                .andExpect(jsonPath("$.detalhes.campos.bruto")
                        .value("deve ser um valor positivo com até 2 casas decimais"))
                .andExpect(jsonPath("$.detalhes.campos.liquido")
                        .value("deve ser um valor positivo com até 2 casas decimais"))
                .andExpect(jsonPath("$.detalhes.campos.conta").value("deve ser um ID válido"))
                .andExpect(jsonPath("$.detalhes.campos.recebidoEm").value("deve estar no formato AAAA-MM-DD"));
        alterar(id, Map.of("liquido", "1.234")).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.liquido")
                        .value("deve ser um valor positivo com até 2 casas decimais"));
        alterar(UUID.randomUUID(), Map.of("descricao", "x")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("GANHO_NAO_ENCONTRADO"));
        saldo(nubank, "3850.00");
    }

    @Test
    void excluir_e_restaurar_refletem_no_saldo() throws Exception {
        var id = UUID.randomUUID();
        registrar(id, ganho("FREELA", "1000", "850", nubank, "2026-09-10")).andExpect(status().isCreated());

        mvc.perform(delete(GANHOS + "/" + id)).andExpect(status().isNoContent());
        saldo(nubank, "3000.00");
        listar("2026-09").andExpect(jsonPath("$", hasSize(0)));
        registrar(id, ganho("FREELA", "1000", "850", nubank, "2026-09-10")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("GANHO_EXCLUIDO"));

        mvc.perform(post(GANHOS + "/" + id + "/restaurar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.liquido").value("850.00"));
        saldo(nubank, "3850.00");
        mvc.perform(post(GANHOS + "/" + UUID.randomUUID() + "/restaurar")).andExpect(status().isNotFound());
    }

    @Test
    void listagem_da_competencia_traz_bruto_liquido_tipo_e_recebimentos() throws Exception {
        var freela = UUID.randomUUID();
        var reembolso = UUID.randomUUID();
        registrar(freela, ganho("FREELA", "1000", "850", nubank, "2026-09-10")).andExpect(status().isCreated());
        registrar(reembolso, ganho("REEMBOLSO", "120", "120", inter, "2026-09-12")).andExpect(status().isCreated());

        listar("2026-09").andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].tipo", containsInAnyOrder("FREELA", "REEMBOLSO")))
                .andExpect(jsonPath("$[?(@.id == '" + freela + "')].bruto").value("1000.00"))
                .andExpect(jsonPath("$[?(@.id == '" + reembolso + "')].recebimentos[0].conta")
                        .value(inter.toString()));
        mvc.perform(get(GANHOS).param("competencia", "set")).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("COMPETENCIA_INVALIDA"));
    }

    @Test
    void ganho_do_holerite_nao_muda_pela_api() throws Exception {
        var holerite = new MontadorDeFluxo(jdbc).holerite();
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO fluxo.ganhos (id, competencia, descricao, tipo, bruto, liquido, holerite_id)
                        VALUES (:id, DATE '2026-09-01', 'Salário', 'SALARIO', 5000, 3800, :h)""")
                .param("id", id).param("h", holerite).update();

        alterar(id, Map.of("descricao", "x")).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("GANHO_DO_HOLERITE"));
        mvc.perform(delete(GANHOS + "/" + id)).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("GANHO_DO_HOLERITE"));
        listar("2026-09").andExpect(jsonPath("$[0].holerite").value(holerite.toString()))
                .andExpect(jsonPath("$[0].recebimentos", hasSize(0)));
    }

    private UUID criarConta(String apelido) throws Exception {
        var corpo = Map.of("apelido", apelido + " " + UUID.randomUUID(), "instituicao", "Banco", "tipo", "CORRENTE",
                "funcao", "GIRO", "saldoInicial", "3000", "dataSaldoInicial", "2026-08-31");
        var resposta = mvc.perform(post("/api/contas").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(corpo)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resposta).get("id").asString());
    }

    private ResultActions registrar(UUID id, Map<String, Object> corpo) throws Exception {
        return mvc.perform(put(GANHOS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    private ResultActions alterar(UUID id, Map<String, Object> corpo) throws Exception {
        return mvc.perform(patch(GANHOS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    private ResultActions listar(String competencia) throws Exception {
        return mvc.perform(get(GANHOS).param("competencia", competencia)).andExpect(status().isOk());
    }

    private void saldo(UUID conta, String esperado) throws Exception {
        mvc.perform(get("/api/contas/" + conta + "/saldo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.atual").value(esperado));
    }

    private static Map<String, Object> ganho(String tipo, String bruto, String liquido, UUID conta,
                                             String recebidoEm) {
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("competencia", recebidoEm.substring(0, 7));
        corpo.put("descricao", "Projeto");
        corpo.put("tipo", tipo);
        corpo.put("bruto", bruto);
        corpo.put("liquido", liquido);
        corpo.put("conta", conta.toString());
        corpo.put("recebidoEm", recebidoEm);
        return corpo;
    }
}
