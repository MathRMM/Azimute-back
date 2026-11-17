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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Adiantamento lançado antes do holerite (RF34, RN18, RF38). */
@SpringBootTest
@Import(TestcontainersConfig.class)
class AdiantamentoIT {

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    ApiDaFolha api;

    @BeforeEach
    void preparar() throws Exception {
        api = new ApiDaFolha(ClienteDeTeste.autenticado(contexto, jdbc), json, jdbc).comContas();
    }

    @Test
    void lancar_antes_credita_na_data_padrao_e_a_importacao_associa_sem_duplicar() throws Exception {
        api.configurada();
        var id = UUID.randomUUID();

        api.adiantamento(id, Map.of("competencia", "2026-10", "valor", "2500")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.competencia").value("2026-10"))
                .andExpect(jsonPath("$.valor").value("2500.00"))
                .andExpect(jsonPath("$.data").value("2026-10-15"))
                .andExpect(jsonPath("$.conta").value(api.salario.toString()));
        api.saldo(api.salario, "5500.00");
        api.adiantamento(id, Map.of("competencia", "2026-10", "valor", "2500")).andExpect(status().isOk());

        api.importar(ApiDaFolha.holerite("2026-10")).andExpect(status().isCreated());

        api.saldo(api.salario, "6920.00");
        api.consultar("/api/ganhos", "competencia", "2026-10")
                .andExpect(jsonPath("$[0].recebimentos", hasSize(2)))
                .andExpect(jsonPath("$[0].recebimentos[*].id", hasItem(id.toString())));
    }

    @Test
    void valor_diferente_do_holerite_e_ajustado_e_a_data_lancada_e_mantida() throws Exception {
        api.configurada();
        var id = UUID.randomUUID();
        api.adiantamento(id, Map.of("competencia", "2026-10", "valor", "2400", "data", "2026-10-14"))
                .andExpect(status().isCreated());

        api.importar(ApiDaFolha.holerite("2026-10")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.dataAdiantamento").value("2026-10-14"));

        api.saldo(api.salario, "6920.00");
        api.consultar("/api/ganhos", "competencia", "2026-10")
                .andExpect(jsonPath("$[0].recebimentos[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].recebimentos[0].valor").value("2500.00"))
                .andExpect(jsonPath("$[0].recebimentos[0].data").value("2026-10-14"));
    }

    @Test
    void excluir_o_holerite_solta_o_adiantamento_e_restaurar_associa_de_novo() throws Exception {
        api.configurada();
        var id = UUID.randomUUID();
        api.adiantamento(id, Map.of("competencia", "2026-10", "valor", "2500")).andExpect(status().isCreated());
        var holerite = api.importarCriando(ApiDaFolha.holerite("2026-10"));

        api.excluir(holerite).andExpect(status().isNoContent());
        api.saldo(api.salario, "5500.00");
        api.adiantamento(UUID.randomUUID(), Map.of("competencia", "2026-10", "valor", "1")).andExpect(status()
                .isConflict()).andExpect(jsonPath("$.erro").value("ADIANTAMENTO_JA_LANCADO"));

        api.restaurar(holerite).andExpect(status().isOk());
        api.saldo(api.salario, "6920.00");
        api.consultar("/api/ganhos", "competencia", "2026-10")
                .andExpect(jsonPath("$[0].recebimentos", hasSize(2)))
                .andExpect(jsonPath("$[0].recebimentos[*].id", hasItem(id.toString())));
    }

    @Test
    void regras_do_adiantamento() throws Exception {
        var id = UUID.randomUUID();
        api.adiantamento(id, Map.of("competencia", "2026-10", "valor", "2500")).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("FOLHA_NAO_CONFIGURADA"));

        api.configurada();
        api.adiantamento(id, Map.of("competencia", "2026-10", "valor", "2500")).andExpect(status().isCreated());
        api.adiantamento(id, Map.of("competencia", "2026-10", "valor", "2400")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("ADIANTAMENTO_DIVERGENTE"));
        api.adiantamento(UUID.randomUUID(), Map.of("competencia", "2026-10", "valor", "10"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.erro").value("ADIANTAMENTO_JA_LANCADO"));
        api.adiantamento(UUID.randomUUID(), Map.of("competencia", "2026-13", "valor", "0"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.competencia").value("deve estar no formato AAAA-MM"))
                .andExpect(jsonPath("$.detalhes.campos.valor").value("deve ser maior que zero"));

        api.importar(ApiDaFolha.holerite("2026-11")).andExpect(status().isCreated());
        api.adiantamento(UUID.randomUUID(), Map.of("competencia", "2026-11", "valor", "2500"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.erro").value("COMPETENCIA_COM_HOLERITE"));
    }
}
