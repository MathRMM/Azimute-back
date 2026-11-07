package br.dev.azimute.auth;

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

/** Perfil dev (local, sem proxy nem certificado): HTTP puro é aceito, mas o token continua obrigatório (D1). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfig.class)
class HttpDevIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void http_puro_e_aceito_com_token() throws Exception {
        var token = ClienteDeTeste.novoDispositivo(jdbc, "dev");
        mvc.perform(get("/api/calculadora/historico").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void token_continua_obrigatorio_no_dev() throws Exception {
        mvc.perform(get("/api/calculadora/historico")).andExpect(status().isUnauthorized());
    }
}
