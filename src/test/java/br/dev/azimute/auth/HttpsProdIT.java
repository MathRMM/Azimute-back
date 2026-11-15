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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Perfil prod atrás do Caddy: a aplicação recebe HTTP na rede interna e só sabe que a conexão original era
 * HTTPS pelo X-Forwarded-Proto (SEC12, D1, T4). O MockMvc daqui inclui os filtros do Boot, inclusive o
 * ForwardedHeaderFilter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Import(TestcontainersConfig.class)
class HttpsProdIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void http_puro_e_rejeitado_mesmo_com_token() throws Exception {
        var token = ClienteDeTeste.novoDispositivo(jdbc, "prod");
        mvc.perform(get("/api/calculadora/historico").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erro").value("HTTPS_OBRIGATORIO"));
    }

    @Test
    void http_puro_no_cadastro_de_dispositivo_e_rejeitado() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/dispositivos"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erro").value("HTTPS_OBRIGATORIO"));
    }

    @Test
    void https_informado_pelo_proxy_e_aceito() throws Exception {
        var token = ClienteDeTeste.novoDispositivo(jdbc, "prod");
        mvc.perform(get("/api/calculadora/historico")
                        .header("X-Forwarded-Proto", "https")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void health_responde_por_http_para_o_healthcheck_interno() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
