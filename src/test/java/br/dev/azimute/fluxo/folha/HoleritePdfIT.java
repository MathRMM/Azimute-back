package br.dev.azimute.fluxo.folha;

import br.dev.azimute.ClienteDeTeste;
import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Upload do PDF, listagem e download (RF31, RF37, RNF05, SEC02, SEC04, SEC06, T8, T12). Com o log do sistema em
 * DEBUG, para provar que nada do holerite vai parar nele.
 */
@SpringBootTest(properties = "logging.level.br.dev.azimute=DEBUG")
@Import(TestcontainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class HoleritePdfIT {

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    MockMvc mvc;
    ApiDaFolha api;

    @BeforeEach
    void preparar() throws Exception {
        mvc = ClienteDeTeste.autenticado(contexto, jdbc);
        api = new ApiDaFolha(mvc, json, jdbc).comContas().configurada();
    }

    @Test
    void upload_do_sintetico_da_o_mesmo_resultado_que_o_lancamento_manual() throws Exception {
        var inicio = System.nanoTime();
        var pdf = json.readTree(enviar(HoleriteSintetico.doExemplo().pdf()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.origem").value("PDF"))
                .andReturn().getResponse().getContentAsString());
        var milissegundos = (System.nanoTime() - inicio) / 1_000_000;
        api.saldo(api.salario, "6920.00");
        api.saldo(api.fgts, "400.00");
        api.saldo(api.previdencia, "350.00");

        api.excluir(UUID.fromString(pdf.get("id").asString()));
        var manual = json.readTree(api.importar(ApiDaFolha.holerite("2026-10")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        // As rubricas do PDF trazem a referência, que o JSON manual do exemplo omite; o resto é igual.
        assertThat(pdf.get("rubricas")).hasSize(6);
        assertThat(comparavel(pdf)).isEqualTo(comparavel(manual));
        assertThat(pdf.get("rubricas").get(5).get("desconto").asString()).isEqualTo("2500.00");
        api.saldo(api.salario, "6920.00");
        // RNF05: a importação responde em menos de 2 s (aqui, com o contexto do Spring já de pé).
        assertThat(milissegundos).isLessThan(2000);
    }

    @Test
    void mesmo_pdf_reenviado_e_200_e_o_download_devolve_o_arquivo_identico() throws Exception {
        var arquivo = HoleriteSintetico.doExemplo().pdf();
        var id = api.id(enviar(arquivo).andExpect(status().isCreated()));

        enviar(arquivo).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        var baixado = mvc.perform(get("/api/folha/holerites/" + id + "/pdf")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"holerite-2026-10.pdf\""))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(baixado).isEqualTo(arquivo);
        assertThat(jdbc.sql("SELECT count(*) FROM fluxo.holerite_arquivos").query(Long.class).single()).isOne();
    }

    @Test
    void listagem_por_ano_e_detalhe_com_as_rubricas() throws Exception {
        var outubro = api.id(enviar(HoleriteSintetico.doExemplo().pdf()).andExpect(status().isCreated()));
        var manualDeNovembro = api.importarCriando(ApiDaFolha.holerite("2026-11"));
        api.importarCriando(ApiDaFolha.holerite("2027-01"));

        mvc.perform(get("/api/folha/holerites").param("ano", "2026")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].competencia").value("2026-10"))
                .andExpect(jsonPath("$[0].origem").value("PDF"))
                .andExpect(jsonPath("$[0].liquido").value("1420.00"))
                .andExpect(jsonPath("$[1].competencia").value("2026-11"));
        mvc.perform(get("/api/folha/holerites/" + outubro)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rubricas", hasSize(6)))
                .andExpect(jsonPath("$.previdencia.total").value("350.00"));

        mvc.perform(get("/api/folha/holerites/" + manualDeNovembro + "/pdf")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("PDF_NAO_ENCONTRADO"));
        mvc.perform(get("/api/folha/holerites/" + UUID.randomUUID())).andExpect(status().isNotFound());
        api.excluir(outubro);
        mvc.perform(get("/api/folha/holerites/" + outubro)).andExpect(status().isNotFound());
        mvc.perform(get("/api/folha/holerites").param("ano", "2026")).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void arquivo_que_nao_e_pdf_e_422_e_maior_que_2_mb_e_413() throws Exception {
        enviar("não sou um PDF".getBytes()).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("ARQUIVO_NAO_E_PDF"));
        var grande = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-".getBytes(), 0, grande, 0, 5);
        enviar(grande).andExpect(status().is(413)).andExpect(jsonPath("$.erro").value("ARQUIVO_GRANDE_DEMAIS"));

        assertThat(jdbc.sql("SELECT count(*) FROM fluxo.holerites").query(Long.class).single()).isZero();
    }

    @Test
    void pdf_ilegivel_e_422_com_o_campo_e_nada_e_gravado() throws Exception {
        enviar(HoleriteSintetico.doExemplo().semLiquido().pdf()).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("HOLERITE_ILEGIVEL"))
                .andExpect(jsonPath("$.detalhes.campo").value("liquido"));
        enviar(HoleriteSintetico.doExemplo().comPaginasExtras(1).pdf()).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campo").value("paginas"));

        assertThat(jdbc.sql("SELECT count(*) FROM fluxo.holerites").query(Long.class).single()).isZero();
        api.saldo(api.salario, "3000.00");
    }

    @Test
    void datas_do_upload_prevalecem_e_sem_arquivo_e_400() throws Exception {
        mvc.perform(multipart("/api/folha/holerites")
                        .file(new MockMultipartFile("arquivo", "h.pdf", "application/pdf",
                                HoleriteSintetico.doExemplo().pdf()))
                        .param("dataAdiantamento", "2026-10-16").param("dataLiquido", "2026-11-02"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dataAdiantamento").value("2026-10-16"))
                .andExpect(jsonPath("$.dataLiquido").value("2026-11-02"));
        mvc.perform(multipart("/api/folha/holerites")).andExpect(status().isBadRequest());
    }

    @Test
    void nada_do_holerite_aparece_no_log(CapturedOutput saida) throws Exception {
        enviar(HoleriteSintetico.doExemplo().pdf()).andExpect(status().isCreated());
        enviar(HoleriteSintetico.doExemplo().semLiquido().naCompetencia("Novembro 2026").pdf())
                .andExpect(status().is(422));
        var inconsistente = HoleriteSintetico.doExemplo().naCompetencia("Dezembro 2026")
                .comTotais("5.000,00", "3.580,00", "1.400,00").pdf();
        enviar(inconsistente).andExpect(status().is(422));

        assertThat(saida.getAll()).contains("HOLERITE_ILEGIVEL").contains("HOLERITE_INCONSISTENTE")
                .doesNotContain("FULANO", "EMPRESA FICTICIA", "00012345", "5.000,00", "1.420,00", "5000.00",
                        "1420.00", "1400.00", "Salário Mensal");
    }

    private ResultActions enviar(byte[] arquivo) throws Exception {
        return mvc.perform(multipart("/api/folha/holerites")
                .file(new MockMultipartFile("arquivo", "holerite.pdf", MediaType.APPLICATION_PDF_VALUE, arquivo)));
    }

    private static JsonNode comparavel(JsonNode holerite) {
        var copia = (ObjectNode) holerite.deepCopy();
        copia.remove("id");
        copia.remove("origem");
        copia.remove("rubricas");
        return copia;
    }
}
