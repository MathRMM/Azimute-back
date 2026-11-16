package br.dev.azimute.fluxo.folha;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chamadas à API usadas pelos testes de integração da folha, com o holerite do fluxo principal A do PRD (valores
 * fictícios, SEC03). O banco é limpo a cada teste, menos o catálogo e as regras de contrapartida.
 */
final class ApiDaFolha {

    private final MockMvc mvc;
    private final JsonMapper json;

    UUID salario;
    UUID fgts;
    UUID previdencia;

    ApiDaFolha(MockMvc mvc, JsonMapper json, JdbcClient jdbc) {
        this.mvc = mvc;
        this.json = json;
        jdbc.sql("""
                TRUNCATE contas.conciliacoes, contas.movimentacoes, contas.contas, fluxo.holerites, fluxo.ganhos
                CASCADE""").update();
    }

    /** Contas do salário, do FGTS e da previdência, com base em 31/08/2026 (salário 3.000, as outras zero). */
    ApiDaFolha comContas() throws Exception {
        salario = criarConta("Salário", "CORRENTE", "GIRO", "3000");
        fgts = criarConta("FGTS", "FGTS", "RESERVA", "0");
        previdencia = criarConta("Previdência", "PREVIDENCIA", "RESERVA", "0");
        return this;
    }

    ApiDaFolha configurada() throws Exception {
        configurar(salario, fgts, previdencia).andExpect(status().isOk());
        return this;
    }

    UUID criarConta(String apelido, String tipo, String funcao, String saldo) throws Exception {
        var corpo = Map.of("apelido", apelido + " " + UUID.randomUUID(), "instituicao", "Banco", "tipo", tipo,
                "funcao", funcao, "saldoInicial", saldo, "dataSaldoInicial", "2026-08-31");
        return id(mvc.perform(post("/api/contas").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo))).andExpect(status().isCreated()));
    }

    ResultActions configurar(UUID salario, UUID fgts, UUID previdencia) throws Exception {
        return enviar(put("/api/folha/configuracao"), Map.of("contaSalario", salario.toString(),
                "contaFgts", fgts.toString(), "contaPrevidencia", previdencia.toString()));
    }

    /** O holerite do exemplo, na competência dada, como mapa editável. */
    static Map<String, Object> holerite(String competencia) {
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("competencia", competencia);
        corpo.put("rubricas", new ArrayList<>(List.of(
                rubrica("0001", "Salário Mensal", "5000", null),
                rubrica("/314", "INSS", null, "480"),
                rubrica("/507", "Vale-Transporte", null, "300"),
                rubrica("2363", "Prev Priv Bas Func", null, "50"),
                rubrica("2364", "Prev Priv Vol Func", null, "250"),
                rubrica("/B02", "Decto Adto Salário", null, "2500"))));
        corpo.put("totalVencimentos", "5000");
        corpo.put("totalDescontos", "3580");
        corpo.put("liquido", "1420");
        corpo.put("bases", Map.of("salarioBase", "5000", "baseInss", "5000", "baseFgts", "5000", "fgtsMes", "400",
                "baseIrrf", "4170"));
        return corpo;
    }

    static Map<String, Object> rubrica(String codigo, String descricao, String vencimento, String desconto) {
        var r = new LinkedHashMap<String, Object>();
        r.put("codigo", codigo);
        r.put("descricao", descricao);
        if (vencimento != null) {
            r.put("vencimento", vencimento);
        }
        if (desconto != null) {
            r.put("desconto", desconto);
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    static void acrescentar(Map<String, Object> holerite, Map<String, Object> rubrica, String totalDescontos,
                            String liquido) {
        ((List<Object>) holerite.get("rubricas")).add(rubrica);
        holerite.put("totalDescontos", totalDescontos);
        holerite.put("liquido", liquido);
    }

    ResultActions importar(Map<String, Object> holerite) throws Exception {
        return enviar(post("/api/folha/holerites/manual"), holerite);
    }

    UUID importarCriando(Map<String, Object> holerite) throws Exception {
        return id(importar(holerite).andExpect(status().isCreated()));
    }

    ResultActions adiantamento(UUID id, Map<String, Object> corpo) throws Exception {
        return enviar(put("/api/folha/adiantamentos/" + id), corpo);
    }

    ResultActions excluir(UUID holerite) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/folha/holerites/" + holerite));
    }

    ResultActions restaurar(UUID holerite) throws Exception {
        return mvc.perform(post("/api/folha/holerites/" + holerite + "/restaurar"));
    }

    ResultActions corrigirPrevidencia(UUID holerite, Object contrapartida) throws Exception {
        return enviar(patch("/api/folha/holerites/" + holerite + "/previdencia"),
                Map.of("contrapartida", contrapartida));
    }

    ResultActions reclassificar(String codigo, Map<String, Object> corpo) throws Exception {
        return mvc.perform(patch("/api/folha/rubricas").param("codigo", codigo)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo)));
    }

    ResultActions consultar(String caminho, String... parametros) throws Exception {
        var pedido = get(caminho);
        for (int i = 0; i < parametros.length; i += 2) {
            pedido.param(parametros[i], parametros[i + 1]);
        }
        return mvc.perform(pedido);
    }

    ResultActions enviar(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pedido,
                         Object corpo) throws Exception {
        return mvc.perform(pedido.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo)));
    }

    void saldo(UUID conta, String esperado) throws Exception {
        mvc.perform(get("/api/contas/" + conta + "/saldo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.atual").value(esperado));
    }

    UUID id(ResultActions resposta) throws Exception {
        var corpo = resposta.andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(corpo).get("id").asString());
    }

    UUID categoria(String nome) throws Exception {
        var corpo = mvc.perform(get("/api/categorias")).andReturn().getResponse().getContentAsString();
        for (var c : json.readTree(corpo)) {
            if (c.get("nome").asString().equals(nome)) {
                return UUID.fromString(c.get("id").asString());
            }
        }
        throw new IllegalStateException("Categoria não encontrada: " + nome);
    }
}
