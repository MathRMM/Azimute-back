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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transferências, rendimentos, exclusão lógica, restauração e extrato contra o Postgres real.
 * Refs: RF10–RF15, RN02, RN04, RN05, RN08, RN10, RN11, RN14, RN15, P1, P2.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class MovimentacoesIT {

    private static final String CONTAS = "/api/contas";
    private static final String MOVIMENTACOES = "/api/contas/movimentacoes/";

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
        nubank = criarConta("Nubank", "CORRENTE", "GIRO", "3000", "2026-08-31");
        cdb = criarConta("CDB Liquidez", "INVESTIMENTO", "RESERVA", "9000", "2026-08-31");
    }

    @Test
    void transferencia_debita_a_origem_e_credita_o_destino() throws Exception {
        var id = UUID.randomUUID();

        registrar(id, transferencia(nubank, cdb, "1500", "2026-09-05")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.tipo").value("TRANSFERENCIA"))
                .andExpect(jsonPath("$.origem").value(nubank.toString()))
                .andExpect(jsonPath("$.destino").value(cdb.toString()))
                .andExpect(jsonPath("$.valor").value("1500.00"))
                .andExpect(jsonPath("$.motivo").value("reserva"));

        saldo(nubank).andExpect(jsonPath("$.atual").value("1500.00"))
                .andExpect(jsonPath("$.movimentacoesDesdeABase").value(1));
        saldo(cdb).andExpect(jsonPath("$.atual").value("10500.00"));
    }

    @Test
    void reenvio_identico_nao_duplica_e_reenvio_diferente_e_409() throws Exception {
        var id = UUID.randomUUID();
        var primeira = registrar(id, transferencia(nubank, cdb, "100", "2026-09-05"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        var segunda = registrar(id, transferencia(nubank, cdb, "100", "2026-09-05"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        registrar(id, transferencia(nubank, cdb, "101", "2026-09-05"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("MOVIMENTACAO_DIVERGENTE"));

        assertThat(segunda).isEqualTo(primeira);
        assertThat(quantidade()).isEqualTo(1);
        saldo(nubank).andExpect(jsonPath("$.atual").value("2900.00"));
    }

    @Test
    void dois_envios_simultaneos_do_mesmo_id_geram_um_registro() throws Exception {
        var id = UUID.randomUUID();
        var corpo = transferencia(nubank, cdb, "50", "2026-09-05");
        Callable<Integer> envio = () -> registrar(id, corpo).andReturn().getResponse().getStatus();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(envio);
            var b = executor.submit(envio);
            assertThat(a.get()).isEqualTo(200);
            assertThat(b.get()).isEqualTo(200);
        }
        assertThat(quantidade()).isEqualTo(1);
    }

    @Test
    void rendimento_credita_a_conta() throws Exception {
        registrar(UUID.randomUUID(), rendimento(cdb, "42", "2026-09-30")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("RENDIMENTO"))
                .andExpect(jsonPath("$.origem").doesNotExist());

        saldo(cdb).andExpect(jsonPath("$.atual").value("9042.00"));
    }

    @Test
    void regras_de_validacao_da_movimentacao() throws Exception {
        registrar(UUID.randomUUID(), transferencia(nubank, nubank, "10", "2026-09-05"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("TRANSFERENCIA_PARA_A_MESMA_CONTA"));

        var saida = new LinkedHashMap<String, Object>(Map.of("tipo", "SAIDA", "origem", nubank.toString(),
                "valor", "10", "data", "2026-09-05"));
        registrar(UUID.randomUUID(), saida).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("TIPO_NAO_PERMITIDO"));

        registrar(UUID.randomUUID(), transferencia(nubank, cdb, "0", "2026-09-05"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.detalhes.campos.valor").exists());
        registrar(UUID.randomUUID(), transferencia(nubank, cdb, "-5", "2026-09-05"))
                .andExpect(status().is(422));
        registrar(UUID.randomUUID(), transferencia(nubank, cdb, "1.001", "2026-09-05"))
                .andExpect(status().is(422));

        var semData = transferencia(nubank, cdb, "10", "2026-09-05");
        semData.remove("data");
        registrar(UUID.randomUUID(), semData).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.data").exists());
        assertThat(quantidade()).isZero();
    }

    @Test
    void transferencia_que_deixa_a_origem_negativa_e_aceita() throws Exception {
        registrar(UUID.randomUUID(), transferencia(nubank, cdb, "5000", "2026-09-05")).andExpect(status().isOk());

        saldo(nubank).andExpect(jsonPath("$.atual").value("-2000.00"));
    }

    @Test
    void data_futura_e_aceita_e_ja_conta_no_saldo_atual() throws Exception {
        var daquiADezDias = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(10).toString();

        registrar(UUID.randomUUID(), transferencia(nubank, cdb, "300", daquiADezDias)).andExpect(status().isOk());

        saldo(nubank).andExpect(jsonPath("$.atual").value("2700.00"));
    }

    @Test
    void conta_inativa_e_conta_inexistente() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(CONTAS + "/" + cdb)
                .contentType(MediaType.APPLICATION_JSON).content("{\"ativa\": false}")).andExpect(status().isOk());

        registrar(UUID.randomUUID(), transferencia(nubank, cdb, "10", "2026-09-05"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.erro").value("CONTA_INATIVA"));
        registrar(UUID.randomUUID(), rendimento(UUID.randomUUID(), "10", "2026-09-05"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.erro").value("CONTA_NAO_ENCONTRADA"));
    }

    @Test
    void excluir_tira_do_saldo_e_do_extrato_mas_mantem_no_banco_e_restaurar_devolve() throws Exception {
        var id = UUID.randomUUID();
        registrar(id, transferencia(nubank, cdb, "1500", "2026-09-05")).andExpect(status().isOk());

        mvc.perform(delete(MOVIMENTACOES + id)).andExpect(status().isNoContent());
        mvc.perform(delete(MOVIMENTACOES + id)).andExpect(status().isNoContent());

        saldo(nubank).andExpect(jsonPath("$.atual").value("3000.00"));
        saldo(cdb).andExpect(jsonPath("$.atual").value("9000.00"));
        extrato(nubank, "2026-09-01", "2026-09-30").andExpect(jsonPath("$.linhas", hasSize(0)));
        var excluidaEm = jdbc.sql("SELECT deleted_at IS NOT NULL FROM contas.movimentacoes WHERE id = :id")
                .param("id", id).query(Boolean.class).single();
        assertThat(excluidaEm).isTrue();

        registrar(id, transferencia(nubank, cdb, "1500", "2026-09-05"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.erro").value("MOVIMENTACAO_EXCLUIDA"));

        mvc.perform(post(MOVIMENTACOES + id + "/restaurar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.valor").value("1500.00"));
        saldo(nubank).andExpect(jsonPath("$.atual").value("1500.00"));
        extrato(nubank, "2026-09-01", "2026-09-30").andExpect(jsonPath("$.linhas", hasSize(1)));
    }

    @Test
    void excluir_inexistente_e_204_e_restaurar_inexistente_e_404() throws Exception {
        mvc.perform(delete(MOVIMENTACOES + UUID.randomUUID())).andExpect(status().isNoContent());
        mvc.perform(post(MOVIMENTACOES + UUID.randomUUID() + "/restaurar")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("MOVIMENTACAO_NAO_ENCONTRADA"));
    }

    @Test
    void movimentacao_de_outro_modulo_nao_e_excluida_por_aqui() throws Exception {
        var saida = jdbc.sql("""
                        INSERT INTO contas.movimentacoes (tipo, conta_origem, valor, data, gasto_id)
                        VALUES ('SAIDA', :conta, 10, DATE '2026-09-05', :gasto) RETURNING id""")
                .param("conta", nubank).param("gasto", UUID.randomUUID()).query(UUID.class).single();

        mvc.perform(delete(MOVIMENTACOES + saida)).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("MOVIMENTACAO_DE_OUTRO_MODULO"));
    }

    @Test
    void extrato_do_mes_tem_so_o_periodo_com_saldo_inicial_e_final() throws Exception {
        registrar(UUID.randomUUID(), transferencia(nubank, cdb, "200", "2026-08-31")).andExpect(status().isOk());
        registrar(UUID.randomUUID(), transferencia(nubank, cdb, "500", "2026-09-05")).andExpect(status().isOk());
        registrar(UUID.randomUUID(), transferencia(cdb, nubank, "100", "2026-09-20")).andExpect(status().isOk());
        registrar(UUID.randomUUID(), transferencia(nubank, cdb, "999", "2026-10-02")).andExpect(status().isOk());

        extrato(nubank, "2026-09-01", "2026-09-30").andExpect(status().isOk())
                .andExpect(jsonPath("$.saldoInicial").value("3000.00"))
                .andExpect(jsonPath("$.saldoFinal").value("2600.00"))
                .andExpect(jsonPath("$.linhas", hasSize(2)))
                .andExpect(jsonPath("$.linhas[0].data").value("2026-09-05"))
                .andExpect(jsonPath("$.linhas[0].sentido").value("DEBITO"))
                .andExpect(jsonPath("$.linhas[0].contraparte").value(cdb.toString()))
                .andExpect(jsonPath("$.linhas[1].sentido").value("CREDITO"))
                .andExpect(jsonPath("$.linhas[1].valor").value("100.00"))
                .andExpect(jsonPath("$.conciliacoes", hasSize(0)));
    }

    @Test
    void movimentacao_no_dia_da_conciliacao_aparece_no_extrato_sem_mudar_o_saldo() throws Exception {
        registrar(UUID.randomUUID(), transferencia(nubank, cdb, "200", "2026-08-31")).andExpect(status().isOk());

        saldo(nubank).andExpect(jsonPath("$.atual").value("3000.00"));
        extrato(nubank, "2026-08-31", "2026-08-31")
                .andExpect(jsonPath("$.saldoInicial").value("0.00"))
                .andExpect(jsonPath("$.saldoFinal").value("3000.00"))
                .andExpect(jsonPath("$.linhas", hasSize(1)))
                .andExpect(jsonPath("$.conciliacoes", hasSize(1)))
                .andExpect(jsonPath("$.conciliacoes[0].saldoReal").value("3000.00"));
    }

    @Test
    void extrato_sem_movimentacao_e_periodo_invalido() throws Exception {
        extrato(nubank, "2026-09-01", "2026-09-30")
                .andExpect(jsonPath("$.linhas", hasSize(0)))
                .andExpect(jsonPath("$.saldoInicial").value("3000.00"))
                .andExpect(jsonPath("$.saldoFinal").value("3000.00"));

        extrato(nubank, "2026-10-01", "2026-09-30").andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("PERIODO_INVALIDO"));
        extrato(UUID.randomUUID(), "2026-09-01", "2026-09-30").andExpect(status().isNotFound());
    }

    @Test
    void sem_token_e_401() throws Exception {
        var anonimo = ClienteDeTeste.anonimo(contexto);
        anonimo.perform(put(MOVIMENTACOES + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(rendimento(cdb, "1", "2026-09-01"))))
                .andExpect(status().isUnauthorized());
        anonimo.perform(get(CONTAS + "/" + nubank + "/extrato").param("de", "2026-09-01").param("ate", "2026-09-30"))
                .andExpect(status().isUnauthorized());
    }

    private UUID criarConta(String apelido, String tipo, String funcao, String saldo, String data) throws Exception {
        var corpo = Map.of("apelido", apelido, "instituicao", "Banco", "tipo", tipo, "funcao", funcao,
                "saldoInicial", saldo, "dataSaldoInicial", data);
        var resposta = mvc.perform(post(CONTAS).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(corpo)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resposta).get("id").asString());
    }

    private ResultActions registrar(UUID id, Map<String, Object> corpo) throws Exception {
        return mvc.perform(put(MOVIMENTACOES + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    private ResultActions saldo(UUID conta) throws Exception {
        return mvc.perform(get(CONTAS + "/" + conta + "/saldo")).andExpect(status().isOk());
    }

    private ResultActions extrato(UUID conta, String de, String ate) throws Exception {
        return mvc.perform(get(CONTAS + "/" + conta + "/extrato").param("de", de).param("ate", ate));
    }

    private long quantidade() {
        return jdbc.sql("SELECT count(*) FROM contas.movimentacoes").query(Long.class).single();
    }

    private static Map<String, Object> transferencia(UUID origem, UUID destino, String valor, String data) {
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("tipo", "TRANSFERENCIA");
        corpo.put("origem", origem.toString());
        corpo.put("destino", destino.toString());
        corpo.put("valor", valor);
        corpo.put("data", data);
        corpo.put("motivo", "reserva");
        return corpo;
    }

    private static Map<String, Object> rendimento(UUID destino, String valor, String data) {
        var corpo = new LinkedHashMap<String, Object>();
        corpo.put("tipo", "RENDIMENTO");
        corpo.put("destino", destino.toString());
        corpo.put("valor", valor);
        corpo.put("data", data);
        return corpo;
    }
}
