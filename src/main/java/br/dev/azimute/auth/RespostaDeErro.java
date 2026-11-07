package br.dev.azimute.auth;

import br.dev.azimute.plataforma.web.ErroApi;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Erros escritos pelos filtros de segurança, antes do Spring MVC, no mesmo formato {@link ErroApi}. */
final class RespostaDeErro {

    private final JsonMapper json;

    RespostaDeErro(JsonMapper json) {
        this.json = json;
    }

    void escrever(HttpServletResponse resposta, int status, ErroApi erro) throws IOException {
        resposta.setStatus(status);
        resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resposta.getWriter().write(json.writeValueAsString(erro));
    }
}
