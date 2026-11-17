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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API de contas e saldo contra o Postgres real.
 * Refs: RF01, RF03, RF04, RF05, RN06, RN09, RN11, SEC01, P5, P8.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ContasIT {

    private static final String CONTAS = "/api/contas";

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    MockMvc mvc;

    @BeforeEach
    void limpar() {
        mvc = ClienteDeTeste.autenticado(contexto, jdbc);
        // TRUNCATE não dispara o trigger de linha que protege as conciliações. CASCADE: as tabelas do fluxo
        // apontam para contas.contas.
        jdbc.sql("TRUNCATE contas.conciliacoes, contas.movimentacoes, contas.contas CASCADE").update();
    }

    @Test
    void criar_conta_com_saldo_inicial_registra_a_primeira_conciliacao() throws Exception {
        var id = criar(nubank()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.apelido").value("Nubank"))
                .andExpect(jsonPath("$.tipo").value("CORRENTE"))
                .andExpect(jsonPath("$.ativa").value(true))
                .andExpect(jsonPath("$.saldo.atual").value("2300.00"))
                .andExpect(jsonPath("$.saldo.movimentacoesDesdeABase").value(0))
                .andReturn().getResponse().getContentAsString();
        var contaId = UUID.fromString(json.readTree(id).get("id").asString());

        var conciliacao = jdbc.sql("""
                        SELECT saldo_real, saldo_calculado, divergencia, data FROM contas.conciliacoes
                        WHERE conta_id = :id""")
                .param("id", contaId)
                .query((rs, n) -> Map.of("real", rs.getBigDecimal(1), "calculado", rs.getBigDecimal(2),
                        "divergencia", rs.getBigDecimal(3), "data", rs.getDate(4).toLocalDate()))
                .single();
        assertThat(conciliacao.get("real")).isEqualTo(new java.math.BigDecimal("2300.00"));
        assertThat(conciliacao.get("calculado")).isEqualTo(new java.math.BigDecimal("2300.00"));
        assertThat(conciliacao.get("divergencia")).isEqualTo(new java.math.BigDecimal("0.00"));
        assertThat(conciliacao.get("data")).isEqualTo(java.time.LocalDate.parse("2026-11-01"));
    }

    @Test
    void corrente_com_limite_mostra_o_cheque_especial_e_outros_tipos_nao() throws Exception {
        var corpo = nubank();
        corpo.put("limiteChequeEspecial", "1000");
        criar(corpo).andExpect(status().isCreated())
                .andExpect(jsonPath("$.limiteChequeEspecial").value("1000.00"))
                .andExpect(jsonPath("$.saldo.chequeEspecial.disponivel").value("1000.00"))
                .andExpect(jsonPath("$.saldo.chequeEspecial.acimaDoLimite").value(false));

        criar(poupanca()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldo.chequeEspecial").value(nullValue()));
    }

    @Test
    void saldo_inicial_negativo_e_aceito_e_aparece_como_cheque_especial() throws Exception {
        var corpo = nubank();
        corpo.put("saldoInicial", "-300");
        corpo.put("limiteChequeEspecial", "1000");

        var id = idDe(criar(corpo).andExpect(status().isCreated()));

        mvc.perform(get(CONTAS + "/" + id + "/saldo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atual").value("-300.00"))
                .andExpect(jsonPath("$.ultimaConciliacao.data").value("2026-11-01"))
                .andExpect(jsonPath("$.ultimaConciliacao.saldoReal").value("-300.00"))
                .andExpect(jsonPath("$.chequeEspecial.emUso").value("300.00"))
                .andExpect(jsonPath("$.chequeEspecial.disponivel").value("700.00"));
    }

    @Test
    void limite_em_conta_que_nao_e_corrente_e_recusado() throws Exception {
        var corpo = poupanca();
        corpo.put("limiteChequeEspecial", "500");

        criar(corpo).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("LIMITE_SO_EM_CONTA_CORRENTE"));
    }

    @Test
    void apelido_repetido_com_outra_caixa_e_409() throws Exception {
        criar(nubank()).andExpect(status().isCreated());
        var repetido = nubank();
        repetido.put("apelido", " NUBANK ");

        criar(repetido).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("APELIDO_EM_USO"));
    }

    @Test
    void campos_obrigatorios_e_valores_invalidos_sao_422() throws Exception {
        var semApelido = nubank();
        semApelido.remove("apelido");
        criar(semApelido).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalhes.campos.apelido").value("deve ser texto não vazio"));

        var tresCasas = nubank();
        tresCasas.put("saldoInicial", "10.005");
        criar(tresCasas).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.saldoInicial")
                        .value("deve ter até 2 casas decimais"));

        var limiteNegativo = nubank();
        limiteNegativo.put("limiteChequeEspecial", "-1");
        criar(limiteNegativo).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.limiteChequeEspecial")
                        .value("deve ser zero ou mais"));
    }

    @Test
    void tipo_desconhecido_e_requisicao_malformada() throws Exception {
        var corpo = nubank();
        corpo.put("tipo", "CRIPTO");

        criar(corpo).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("REQUISICAO_MALFORMADA"));
    }

    @Test
    void listagem_esconde_inativas_por_padrao() throws Exception {
        criar(nubank()).andExpect(status().isCreated());
        var poupanca = idDe(criar(poupanca()));
        mvc.perform(patch(CONTAS + "/" + poupanca).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ativa\": false}")).andExpect(status().isOk());

        mvc.perform(get(CONTAS)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].apelido").value("Nubank"))
                .andExpect(jsonPath("$[0].saldo.atual").value("2300.00"));
        mvc.perform(get(CONTAS).param("incluirInativas", "true"))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void patch_altera_os_campos_editaveis_e_atualiza_updated_at() throws Exception {
        var id = idDe(criar(nubank()));
        var antes = updatedAt(id);

        mvc.perform(patch(CONTAS + "/" + id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"apelido": "Nubank PJ", "instituicao": "Nu Pagamentos", "funcao": "RESERVA",
                         "limiteChequeEspecial": "250.50", "ativa": false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apelido").value("Nubank PJ"))
                .andExpect(jsonPath("$.instituicao").value("Nu Pagamentos"))
                .andExpect(jsonPath("$.funcao").value("RESERVA"))
                .andExpect(jsonPath("$.limiteChequeEspecial").value("250.50"))
                .andExpect(jsonPath("$.ativa").value(false))
                .andExpect(jsonPath("$.saldo.atual").value("2300.00"));

        assertThat(updatedAt(id)).isAfter(antes);
    }

    @Test
    void patch_com_tipo_ou_campo_desconhecido_e_recusado() throws Exception {
        var id = idDe(criar(nubank()));

        mvc.perform(patch(CONTAS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\": \"POUPANCA\", \"saldo\": \"1\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("CAMPO_NAO_EDITAVEL"))
                .andExpect(jsonPath("$.detalhes.campos[0]").value("saldo"))
                .andExpect(jsonPath("$.detalhes.campos[1]").value("tipo"));
    }

    @Test
    void patch_com_valor_de_tipo_errado_e_422() throws Exception {
        var id = idDe(criar(nubank()));

        mvc.perform(patch(CONTAS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ativa\": \"talvez\", \"funcao\": \"OUTRA\", \"limiteChequeEspecial\": \"1.001\", "
                                + "\"apelido\": 5}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalhes.campos.ativa").value("deve ser true ou false"))
                .andExpect(jsonPath("$.detalhes.campos.funcao").value("deve ser um de [GIRO, RESERVA]"))
                .andExpect(jsonPath("$.detalhes.campos.limiteChequeEspecial")
                        .value("deve ser um valor não negativo com até 2 casas decimais"))
                .andExpect(jsonPath("$.detalhes.campos.apelido").value("deve ser texto"));
    }

    @Test
    void patch_com_apelido_de_outra_conta_e_409() throws Exception {
        criar(nubank());
        var poupanca = idDe(criar(poupanca()));

        mvc.perform(patch(CONTAS + "/" + poupanca).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apelido\": \"nubank\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("APELIDO_EM_USO"));
    }

    @Test
    void conta_inexistente_e_404() throws Exception {
        var inexistente = UUID.randomUUID();

        mvc.perform(get(CONTAS + "/" + inexistente + "/saldo")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("CONTA_NAO_ENCONTRADA"));
        mvc.perform(patch(CONTAS + "/" + inexistente).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ativa\": false}")).andExpect(status().isNotFound());
    }

    @Test
    void sem_token_e_401() throws Exception {
        var anonimo = ClienteDeTeste.anonimo(contexto);

        anonimo.perform(get(CONTAS)).andExpect(status().isUnauthorized());
        anonimo.perform(post(CONTAS).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(nubank()))).andExpect(status().isUnauthorized());
    }

    private ResultActions criar(Map<String, Object> corpo) throws Exception {
        return mvc.perform(post(CONTAS).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    private UUID idDe(ResultActions resposta) throws Exception {
        return UUID.fromString(json.readTree(resposta.andReturn().getResponse().getContentAsString())
                .get("id").asString());
    }

    private java.time.OffsetDateTime updatedAt(UUID id) {
        return jdbc.sql("SELECT updated_at FROM contas.contas WHERE id = :id").param("id", id)
                .query(java.time.OffsetDateTime.class).single();
    }

    private static Map<String, Object> nubank() {
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("apelido", "Nubank");
        corpo.put("instituicao", "Nu Pagamentos");
        corpo.put("tipo", "CORRENTE");
        corpo.put("funcao", "GIRO");
        corpo.put("saldoInicial", "2300");
        corpo.put("dataSaldoInicial", "2026-11-01");
        return corpo;
    }

    private static Map<String, Object> poupanca() {
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("apelido", "Poupança Caixa");
        corpo.put("instituicao", "Caixa");
        corpo.put("tipo", "POUPANCA");
        corpo.put("funcao", "RESERVA");
        corpo.put("saldoInicial", "9000");
        return corpo;
    }
}
