package br.dev.azimute.plataforma.web;

import br.dev.azimute.ClienteDeTeste;
import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fora do dev a documentação não existe: a VPS não expõe o mapa da API, nem para quem tem token. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Import(TestcontainersConfig.class)
class SwaggerProdIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void api_docs_nao_existe_nem_com_token() throws Exception {
        var token = ClienteDeTeste.novoDispositivo(jdbc, "prod-docs");
        mvc.perform(get("/v3/api-docs")
                        .header("X-Forwarded-Proto", "https")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void swagger_ui_nao_existe_nem_com_token() throws Exception {
        var token = ClienteDeTeste.novoDispositivo(jdbc, "prod-ui");
        mvc.perform(get("/swagger-ui/index.html")
                        .header("X-Forwarded-Proto", "https")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void sem_token_a_documentacao_continua_fechada() throws Exception {
        mvc.perform(get("/v3/api-docs").header("X-Forwarded-Proto", "https"))
                .andExpect(status().isUnauthorized());
    }
}
