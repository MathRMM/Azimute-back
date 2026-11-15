package br.dev.azimute.plataforma.web;

import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** No perfil dev a documentação da API abre sem token, para testar os endpoints pelo navegador. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfig.class)
class SwaggerDevIT {

    @Autowired MockMvc mvc;

    @Test
    void api_docs_abre_sem_token_e_lista_os_endpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Azimute Core"))
                .andExpect(jsonPath("$.paths['/api/contas']").exists())
                .andExpect(jsonPath("$.paths['/api/contas/consolidado']").exists())
                .andExpect(jsonPath("$.paths['/api/calculadora/historico']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/dispositivos']").exists());
    }

    @Test
    void api_docs_declara_o_token_do_dispositivo() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.token.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.token.scheme").value("bearer"))
                .andExpect(jsonPath("$.security[*].token").exists());
    }

    @Test
    void cadastro_de_dispositivo_nao_pede_token() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/auth/dispositivos'].post.security").isEmpty());
    }

    @Test
    void swagger_ui_abre_sem_token() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void endpoints_continuam_exigindo_token() throws Exception {
        mvc.perform(get("/api/contas")).andExpect(status().isUnauthorized());
    }
}
