package br.dev.azimute.fluxo.folha;

import br.dev.azimute.ClienteDeTeste;
import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RF39, T10: resumo anual da folha, por mês e no ano, com o acumulado das contas FGTS e previdência. */
@SpringBootTest
@Import(TestcontainersConfig.class)
class ResumoFolhaIT {

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    ApiDaFolha api;

    @BeforeEach
    void preparar() throws Exception {
        api = new ApiDaFolha(ClienteDeTeste.autenticado(contexto, jdbc), json, jdbc).comContas();
    }

    @Test
    void dois_holerites_no_ano_somam_por_mes_e_no_ano_com_a_previdencia_separada() throws Exception {
        api.configurada();
        api.importarCriando(ApiDaFolha.holerite("2026-10"));
        api.importarCriando(ApiDaFolha.holerite("2026-11"));
        api.importarCriando(ApiDaFolha.holerite("2027-01"));

        api.consultar("/api/folha/resumo", "ano", "2026").andExpect(status().isOk())
                .andExpect(jsonPath("$.ano").value(2026))
                .andExpect(jsonPath("$.meses", hasSize(2)))
                .andExpect(jsonPath("$.meses[0].competencia").value("2026-10"))
                .andExpect(jsonPath("$.meses[0].bruto").value("5000.00"))
                .andExpect(jsonPath("$.meses[0].inss").value("480.00"))
                .andExpect(jsonPath("$.meses[0].irrf").value("0.00"))
                .andExpect(jsonPath("$.meses[0].impostos").value("480.00"))
                .andExpect(jsonPath("$.meses[0].previdenciaBasica").value("50.00"))
                .andExpect(jsonPath("$.meses[0].previdenciaContrapartida").value("50.00"))
                .andExpect(jsonPath("$.meses[0].previdenciaVoluntaria").value("250.00"))
                .andExpect(jsonPath("$.meses[0].previdenciaTotal").value("350.00"))
                .andExpect(jsonPath("$.meses[0].fgts").value("400.00"))
                .andExpect(jsonPath("$.meses[0].liquidoRecebido").value("3920.00"))
                .andExpect(jsonPath("$.meses[1].competencia").value("2026-11"))
                .andExpect(jsonPath("$.total.bruto").value("10000.00"))
                .andExpect(jsonPath("$.total.impostos").value("960.00"))
                .andExpect(jsonPath("$.total.previdenciaTotal").value("700.00"))
                .andExpect(jsonPath("$.total.fgts").value("800.00"))
                .andExpect(jsonPath("$.total.liquidoRecebido").value("7840.00"))
                .andExpect(jsonPath("$.fgtsAcumulado").value("1200.00"))
                .andExpect(jsonPath("$.previdenciaAcumulada").value("1050.00"));
    }

    @Test
    void reclassificar_uma_rubrica_como_irrf_muda_o_total_de_impostos() throws Exception {
        api.configurada();
        var codigo = "I" + UUID.randomUUID().toString().substring(0, 8);
        var holerite = ApiDaFolha.holerite("2026-10");
        ApiDaFolha.acrescentar(holerite, ApiDaFolha.rubrica(codigo, "Imposto novo", null, "100"), "3680", "1320");
        api.importarCriando(holerite);
        api.consultar("/api/folha/resumo", "ano", "2026").andExpect(jsonPath("$.total.impostos").value("480.00"));

        api.reclassificar(codigo, Map.of("natureza", "IRRF")).andExpect(status().isOk());

        api.consultar("/api/folha/resumo", "ano", "2026")
                .andExpect(jsonPath("$.total.irrf").value("100.00"))
                .andExpect(jsonPath("$.total.impostos").value("580.00"));
    }

    @Test
    void ano_sem_holerite_e_tudo_zero_e_sem_configuracao_o_acumulado_e_nulo() throws Exception {
        api.consultar("/api/folha/resumo", "ano", "2031").andExpect(status().isOk())
                .andExpect(jsonPath("$.meses", hasSize(0)))
                .andExpect(jsonPath("$.total.bruto").value("0.00"))
                .andExpect(jsonPath("$.total.liquidoRecebido").value("0.00"))
                .andExpect(jsonPath("$.fgtsAcumulado").doesNotExist())
                .andExpect(jsonPath("$.previdenciaAcumulada").doesNotExist());
        api.consultar("/api/folha/resumo", "ano", "dois mil").andExpect(status().isBadRequest());
    }
}
