package br.dev.azimute.plataforma.web;

import br.dev.azimute.comum.Dinheiro;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RF06: os caminhos menos comuns da tradução de erros, sem depender de um controller real. */
class TradutorDeErrosBordasTest {

    private final TradutorDeErros tradutor = new TradutorDeErros();

    @Test
    void erro_global_do_objeto_entra_nos_campos_e_so_a_primeira_mensagem_de_cada_campo_vale() throws Exception {
        var resultado = new BeanPropertyBindingResult(new Object(), "pedido");
        resultado.addError(new FieldError("pedido", "nome", "obrigatório"));
        resultado.addError(new FieldError("pedido", "nome", "segunda mensagem"));
        resultado.addError(new ObjectError("pedido", "datas incoerentes"));
        var parametro = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);

        var resposta = tradutor.validacao(new MethodArgumentNotValidException(parametro, resultado));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(resposta.getBody().detalhes())
                .containsEntry("campos", Map.of("nome", "obrigatório", "pedido", "datas incoerentes"));
    }

    @Test
    void parametro_de_metodo_invalido_vira_422_com_o_nome_do_parametro() throws Exception {
        var validador = new LocalValidatorFactoryBean();
        validador.afterPropertiesSet();
        var mvc = MockMvcBuilders.standaloneSetup(new ControladorComParametroValidado())
                .setControllerAdvice(tradutor)
                .setValidator(validador)
                .build();

        mvc.perform(get("/teste/positivo").param("n", "-1"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalhes.campos.n").exists());
    }

    @Test
    void tipo_errado_sem_nome_de_propriedade_usa_um_nome_generico() {
        var resposta = tradutor.tipoErrado(new TypeMismatchException("abc", Integer.class));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resposta.getBody().erro()).isEqualTo("PARAMETRO_INVALIDO");
        assertThat(resposta.getBody().detalhes()).containsEntry("parametro", "parâmetro");
    }

    @ParameterizedTest
    @CsvSource({
            "404, ROTA_NAO_ENCONTRADA",
            "406, FORMATO_NAO_ACEITO",
            "415, TIPO_DE_CONTEUDO_NAO_SUPORTADO",
            "409, REQUISICAO_INVALIDA"
    })
    void erros_do_proprio_spring_mantem_o_status_com_um_codigo_estavel(int codigo, String erro) {
        var resposta = tradutor.inesperado(new ErrorResponseException(HttpStatus.valueOf(codigo)));

        assertThat(resposta.getStatusCode().value()).isEqualTo(codigo);
        assertThat(resposta.getBody().erro()).isEqualTo(erro);
    }

    @Test
    void erro_api_sem_detalhes_tem_objeto_vazio() {
        assertThat(new ErroApi("X", "mensagem", null).detalhes()).isEmpty();
    }

    @Test
    void dinheiro_inteiro_no_json_tambem_e_aceito() {
        var json = JsonMapper.builder().addModule(new JacksonKernelConfig().moduloDoKernel()).build();

        assertThat(json.readValue("17430", Dinheiro.class)).isEqualTo(Dinheiro.de("17430.00"));
    }

    @RestController
    static class ControladorComParametroValidado {
        @GetMapping("/teste/positivo")
        void positivo(@RequestParam @Positive int n) {
        }
    }
}
