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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gastos à vista pela API, com o reflexo no saldo da conta (RF10–RF13, RF15, RN01, RN04, RN05, RN07, RN08, RN09).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class GastosIT {

    private static final String GASTOS = "/api/gastos/";

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    MockMvc mvc;

    UUID nubank;
    UUID inter;
    UUID mercado;

    @BeforeEach
    void preparar() throws Exception {
        mvc = ClienteDeTeste.autenticado(contexto, jdbc);
        // CASCADE leva junto os gastos, que apontam para as contas.
        jdbc.sql("TRUNCATE contas.conciliacoes, contas.movimentacoes, contas.contas CASCADE").update();
        nubank = criarConta("Nubank");
        inter = criarConta("Inter");
        mercado = criarCategoria("Mercado " + UUID.randomUUID());
    }

    @Test
    void put_cria_com_201_e_o_saldo_cai_reenvio_e_200_e_corpo_diferente_e_409() throws Exception {
        var id = UUID.randomUUID();

        registrar(id, gasto("120", "2026-09-05", mercado, nubank)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.data").value("2026-09-05"))
                .andExpect(jsonPath("$.descricao").value("Feira"))
                .andExpect(jsonPath("$.valor").value("120.00"))
                .andExpect(jsonPath("$.categoria").value(mercado.toString()))
                .andExpect(jsonPath("$.categoriaNome").isNotEmpty())
                .andExpect(jsonPath("$.forma").value("A_VISTA"))
                .andExpect(jsonPath("$.conta").value(nubank.toString()))
                .andExpect(jsonPath("$.holerite").doesNotExist());
        saldo(nubank, "2880.00");

        registrar(id, gasto("120", "2026-09-05", mercado, nubank)).andExpect(status().isOk())
                .andExpect(jsonPath("$.valor").value("120.00"));
        registrar(id, gasto("121", "2026-09-05", mercado, nubank)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("GASTO_DIVERGENTE"));
        saldo(nubank, "2880.00");
    }

    @Test
    void delete_devolve_o_saldo_e_restaurar_debita_de_novo() throws Exception {
        var id = UUID.randomUUID();
        registrar(id, gasto("120", "2026-09-05", mercado, nubank)).andExpect(status().isCreated());

        mvc.perform(delete(GASTOS + id)).andExpect(status().isNoContent());
        saldo(nubank, "3000.00");
        mvc.perform(delete(GASTOS + id)).andExpect(status().isNoContent());
        registrar(id, gasto("120", "2026-09-05", mercado, nubank)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("GASTO_EXCLUIDO"));
        listar("2026-09").andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(post(GASTOS + id + "/restaurar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.valor").value("120.00"));
        saldo(nubank, "2880.00");
        mvc.perform(post(GASTOS + UUID.randomUUID() + "/restaurar")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("GASTO_NAO_ENCONTRADO"));
    }

    @Test
    void patch_muda_valor_data_e_conta_e_a_saida_acompanha() throws Exception {
        var id = UUID.randomUUID();
        registrar(id, gasto("120", "2026-09-05", mercado, nubank)).andExpect(status().isCreated());

        alterar(id, Map.of("valor", "150", "data", "2026-09-07", "conta", inter.toString(), "descricao", "Feirão"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valor").value("150.00"))
                .andExpect(jsonPath("$.data").value("2026-09-07"))
                .andExpect(jsonPath("$.descricao").value("Feirão"))
                .andExpect(jsonPath("$.conta").value(inter.toString()));

        saldo(nubank, "3000.00");
        saldo(inter, "2850.00");
    }

    @Test
    void erros_do_patch() throws Exception {
        var id = UUID.randomUUID();
        registrar(id, gasto("120", "2026-09-05", mercado, nubank)).andExpect(status().isCreated());
        var inativa = criarCategoria("Antiga " + UUID.randomUUID());
        alterarCategoria(inativa, Map.of("ativa", false));

        alterar(id, Map.of("categoria", inativa.toString())).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("CATEGORIA_INATIVA"));
        alterar(id, Map.of("forma", "DESCONTO_EM_FOLHA")).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("CAMPO_NAO_EDITAVEL"));
        alterar(id, Map.of("valor", "1.234", "data", "ontem", "conta", "x", "categoria", 5, "descricao", " "))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.valor")
                        .value("deve ser um valor positivo com até 2 casas decimais"))
                .andExpect(jsonPath("$.detalhes.campos.data").value("deve estar no formato AAAA-MM-DD"))
                .andExpect(jsonPath("$.detalhes.campos.conta").value("deve ser um ID válido"))
                .andExpect(jsonPath("$.detalhes.campos.categoria").value("deve ser um ID válido"))
                .andExpect(jsonPath("$.detalhes.campos.descricao").value("deve ser texto não vazio"));
        alterar(id, Map.of("valor", "0")).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.valor")
                        .value("deve ser um valor positivo com até 2 casas decimais"));
        // Valor com 3 casas nunca é arredondado, nem no PUT nem no PATCH (caracterização da Fase 1).
        registrar(UUID.randomUUID(), gasto("10.555", "2026-09-05", mercado, nubank)).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.valor")
                        .value("numeric value out of bounds (<17 digits>.<2 digits> expected)"));
        alterar(id, Map.of("conta", UUID.randomUUID().toString())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("CONTA_NAO_ENCONTRADA"));
        alterar(UUID.randomUUID(), Map.of("descricao", "x")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("GASTO_NAO_ENCONTRADO"));
        saldo(nubank, "2880.00");
    }

    @Test
    void categoria_criada_inline_renomeada_e_desativada() throws Exception {
        var nova = criarCategoria("Padaria " + UUID.randomUUID());
        var id = UUID.randomUUID();
        registrar(id, gasto("15", "2026-09-05", nova, nubank)).andExpect(status().isCreated());

        var nome = "Padaria e café " + UUID.randomUUID();
        alterarCategoria(nova, Map.of("nome", nome));
        listar("2026-09").andExpect(jsonPath("$[0].categoriaNome").value(nome));

        alterarCategoria(nova, Map.of("ativa", false));
        registrar(UUID.randomUUID(), gasto("15", "2026-09-06", nova, nubank)).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("CATEGORIA_INATIVA"));
        registrar(id, gasto("15", "2026-09-05", nova, nubank)).andExpect(status().isOk());
        registrar(UUID.randomUUID(), gasto("15", "2026-09-06", UUID.randomUUID(), nubank))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.erro").value("CATEGORIA_NAO_ENCONTRADA"));
    }

    @Test
    void validacao_do_corpo_e_401_sem_token() throws Exception {
        var mensagens = Map.of("1.234", "numeric value out of bounds (<17 digits>.<2 digits> expected)",
                "0", "must be greater than 0", "-5", "must be greater than 0");
        for (var caso : mensagens.entrySet()) {
            registrar(UUID.randomUUID(), gasto(caso.getKey(), "2026-09-05", mercado, nubank))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.detalhes.campos.valor").value(caso.getValue()));
        }
        registrar(UUID.randomUUID(), Map.of("valor", "10")).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.data").value("must not be null"))
                .andExpect(jsonPath("$.detalhes.campos.descricao").value("must not be blank"))
                .andExpect(jsonPath("$.detalhes.campos.categoria").value("must not be null"))
                .andExpect(jsonPath("$.detalhes.campos.conta").value("must not be null"));
        ClienteDeTeste.anonimo(contexto).perform(get("/api/gastos").param("competencia", "2026-09"))
                .andExpect(status().isUnauthorized());
        assertThat(quantidadeDeGastos()).isZero();
    }

    @Test
    void data_futura_e_aceita_e_conta_inativa_ou_inexistente_nao_grava_nada() throws Exception {
        var futuro = LocalDate.now().plusYears(1).toString();
        registrar(UUID.randomUUID(), gasto("10", futuro, mercado, nubank)).andExpect(status().isCreated());

        mvc.perform(patch("/api/contas/" + inter).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ativa\":false}")).andExpect(status().isOk());
        var recusado = UUID.randomUUID();
        registrar(recusado, gasto("10", "2026-09-05", mercado, inter)).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("CONTA_INATIVA"));
        registrar(UUID.randomUUID(), gasto("10", "2026-09-05", mercado, UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.erro").value("CONTA_NAO_ENCONTRADA"));

        assertThat(quantidadeDeGastos()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM contas.movimentacoes WHERE gasto_id = :g")
                .param("g", recusado).query(Long.class).single()).isZero();
    }

    @Test
    void listar_pela_competencia_da_data_mais_recente_primeiro_e_por_categoria() throws Exception {
        var lazer = criarCategoria("Cinema " + UUID.randomUUID());
        var primeiro = UUID.randomUUID();
        var segundo = UUID.randomUUID();
        var terceiro = UUID.randomUUID();
        registrar(primeiro, gasto("10", "2026-09-01", mercado, nubank)).andExpect(status().isCreated());
        registrar(segundo, gasto("20", "2026-09-30", lazer, nubank)).andExpect(status().isCreated());
        registrar(terceiro, gasto("30", "2026-09-15", mercado, nubank)).andExpect(status().isCreated());
        registrar(UUID.randomUUID(), gasto("40", "2026-10-01", mercado, nubank)).andExpect(status().isCreated());
        registrar(UUID.randomUUID(), gasto("50", "2026-08-31", mercado, nubank)).andExpect(status().isCreated());

        listar("2026-09").andExpect(jsonPath("$[*].id",
                contains(segundo.toString(), terceiro.toString(), primeiro.toString())));
        mvc.perform(get("/api/gastos").param("competencia", "2026-09").param("categoria", mercado.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(terceiro.toString(), primeiro.toString())));
        mvc.perform(get("/api/gastos").param("competencia", "2026-13")).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("COMPETENCIA_INVALIDA"));
    }

    @Test
    void gasto_em_folha_so_muda_a_categoria_e_nao_se_exclui_pela_api() throws Exception {
        var holerite = new MontadorDeFluxo(jdbc).holerite();
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO fluxo.gastos (id, data, descricao, valor, categoria_id, forma, holerite_id)
                        VALUES (:id, DATE '2026-09-30', 'Vale-Transporte', 180, :cat, 'DESCONTO_EM_FOLHA', :h)""")
                .param("id", id).param("cat", mercado).param("h", holerite).update();
        var transporte = criarCategoria("Transporte " + UUID.randomUUID());

        alterar(id, Map.of("valor", "200")).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("GASTO_DO_HOLERITE"));
        alterar(id, Map.of("categoria", transporte.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.forma").value("DESCONTO_EM_FOLHA"))
                .andExpect(jsonPath("$.holerite").value(holerite.toString()))
                .andExpect(jsonPath("$.conta").doesNotExist())
                .andExpect(jsonPath("$.categoria").value(transporte.toString()));
        mvc.perform(delete(GASTOS + id)).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("GASTO_DO_HOLERITE"));
        listar("2026-09").andExpect(jsonPath("$[0].forma").value("DESCONTO_EM_FOLHA"));
    }

    private UUID criarConta(String apelido) throws Exception {
        var corpo = Map.of("apelido", apelido + " " + UUID.randomUUID(), "instituicao", "Banco", "tipo", "CORRENTE",
                "funcao", "GIRO", "saldoInicial", "3000", "dataSaldoInicial", "2026-08-31");
        var resposta = mvc.perform(post("/api/contas").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(corpo)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resposta).get("id").asString());
    }

    private UUID criarCategoria(String nome) throws Exception {
        var resposta = mvc.perform(post("/api/categorias").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nome", nome))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resposta).get("id").asString());
    }

    private void alterarCategoria(UUID id, Map<String, Object> corpo) throws Exception {
        mvc.perform(patch("/api/categorias/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo))).andExpect(status().isOk());
    }

    private ResultActions registrar(UUID id, Map<String, Object> corpo) throws Exception {
        return mvc.perform(put(GASTOS + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    private ResultActions alterar(UUID id, Map<String, Object> corpo) throws Exception {
        return mvc.perform(patch(GASTOS + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    private ResultActions listar(String competencia) throws Exception {
        return mvc.perform(get("/api/gastos").param("competencia", competencia)).andExpect(status().isOk());
    }

    private void saldo(UUID conta, String esperado) throws Exception {
        mvc.perform(get("/api/contas/" + conta + "/saldo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.atual").value(esperado));
    }

    private long quantidadeDeGastos() {
        return jdbc.sql("SELECT count(*) FROM fluxo.gastos").query(Long.class).single();
    }

    private static Map<String, Object> gasto(String valor, String data, UUID categoria, UUID conta) {
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("data", data);
        corpo.put("descricao", "Feira");
        corpo.put("valor", valor);
        corpo.put("categoria", categoria.toString());
        corpo.put("conta", conta.toString());
        return corpo;
    }
}
