package br.dev.azimute.plataforma.web;

import br.dev.azimute.comum.ErroDeNegocio;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RF06, SEC06: todo erro sai como { erro, mensagem, detalhes }. */
class TradutorDeErrosTest {

    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        var validador = new LocalValidatorFactoryBean();
        validador.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ControladorDeTeste())
                .setControllerAdvice(new TradutorDeErros())
                .setValidator(validador)
                .build();
    }

    @Test
    void erro_de_negocio_de_conflito_vira_409_com_codigo_mensagem_e_detalhes() throws Exception {
        mvc.perform(get("/teste/conflito"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("PLANO_NAO_SOMA_CEM"))
                .andExpect(jsonPath("$.mensagem").value("Alocações somam 95%, precisam somar 100%"))
                .andExpect(jsonPath("$.detalhes.soma").value(95))
                .andExpect(jsonPath("$.detalhes.faltando").value(5));
    }

    @Test
    void cada_tipo_de_erro_de_negocio_tem_seu_status() throws Exception {
        mvc.perform(get("/teste/tipo").param("tipo", "VALIDACAO")).andExpect(status().is(422));
        mvc.perform(get("/teste/tipo").param("tipo", "NAO_ENCONTRADO")).andExpect(status().isNotFound());
        mvc.perform(get("/teste/tipo").param("tipo", "CONFLITO")).andExpect(status().isConflict());
        mvc.perform(get("/teste/tipo").param("tipo", "NAO_AUTENTICADO")).andExpect(status().isUnauthorized());
        mvc.perform(get("/teste/tipo").param("tipo", "BLOQUEADO")).andExpect(status().isTooManyRequests());
    }

    @Test
    void erro_sem_detalhes_devolve_objeto_vazio() throws Exception {
        mvc.perform(get("/teste/tipo").param("tipo", "NAO_ENCONTRADO"))
                .andExpect(jsonPath("$.detalhes").isMap())
                .andExpect(jsonPath("$.detalhes").isEmpty());
    }

    @Test
    void falha_de_bean_validation_vira_422_com_os_campos() throws Exception {
        mvc.perform(post("/teste/validar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"\",\"prazo\":0}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalhes.campos.nome").exists())
                .andExpect(jsonPath("$.detalhes.campos.prazo").exists());
    }

    @Test
    void json_malformado_vira_400() throws Exception {
        mvc.perform(post("/teste/validar").contentType(MediaType.APPLICATION_JSON).content("{nome:"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("REQUISICAO_MALFORMADA"));
    }

    @Test
    void parametro_de_tipo_errado_vira_400() throws Exception {
        mvc.perform(get("/teste/numero").param("n", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("PARAMETRO_INVALIDO"));
    }

    @Test
    void parametro_obrigatorio_ausente_vira_400_no_formato() throws Exception {
        mvc.perform(get("/teste/numero"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists())
                .andExpect(jsonPath("$.mensagem").exists());
    }

    @Test
    void metodo_nao_suportado_mantem_o_status_e_o_formato() throws Exception {
        mvc.perform(post("/teste/conflito"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.erro").value("METODO_NAO_SUPORTADO"));
    }

    @Test
    void excecao_inesperada_vira_500_sem_vazar_detalhe_tecnico() throws Exception {
        mvc.perform(get("/teste/inesperado"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.erro").value("ERRO_INTERNO"))
                .andExpect(jsonPath("$.detalhes").isEmpty())
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("senha-do-banco"))))
                .andExpect(content().string(not(containsString("at br.dev"))));
    }

    static final class ErroDeTeste extends ErroDeNegocio {
        ErroDeTeste(String codigo, String mensagem, Tipo tipo, Map<String, Object> detalhes) {
            super(codigo, mensagem, tipo, detalhes);
        }
    }

    record Corpo(@NotBlank String nome, @Positive int prazo) {}

    @RestController
    static class ControladorDeTeste {

        @GetMapping("/teste/conflito")
        void conflito() {
            throw new ErroDeTeste("PLANO_NAO_SOMA_CEM", "Alocações somam 95%, precisam somar 100%",
                    ErroDeNegocio.Tipo.CONFLITO, Map.of("soma", 95, "faltando", 5));
        }

        @GetMapping("/teste/tipo")
        void tipo(@RequestParam ErroDeNegocio.Tipo tipo) {
            throw new ErroDeTeste("CODIGO_" + tipo, "mensagem", tipo, Map.of());
        }

        @PostMapping("/teste/validar")
        void validar(@Valid @RequestBody Corpo corpo) {
        }

        @GetMapping("/teste/numero")
        void numero(@RequestParam int n) {
        }

        @GetMapping("/teste/inesperado")
        void inesperado() {
            throw new IllegalStateException("conexão recusada: senha-do-banco");
        }
    }
}
