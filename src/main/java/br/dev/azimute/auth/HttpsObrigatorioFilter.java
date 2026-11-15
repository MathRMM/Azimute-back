package br.dev.azimute.auth;

import br.dev.azimute.plataforma.web.ErroApi;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejeita HTTP puro fora do perfil dev (SEC12, D1, decisão T4).
 * <p>
 * Rejeitar, não redirecionar: quando o redirecionamento acontece, a senha já viajou em texto. Atrás do Caddy,
 * {@code isSecure()} reflete o {@code X-Forwarded-Proto} graças a {@code server.forward-headers-strategy}.
 * <p>
 * Exceção: {@code /actuator/health}, que responde só "UP" e é chamado por HTTP pelo healthcheck interno.
 */
final class HttpsObrigatorioFilter extends OncePerRequestFilter {

    static final String HEALTH = "/actuator/health";

    private final boolean exigir;
    private final RespostaDeErro erro;

    HttpsObrigatorioFilter(boolean exigir, RespostaDeErro erro) {
        this.exigir = exigir;
        this.erro = erro;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        if (exigir && !requisicao.isSecure() && !requisicao.getRequestURI().equals(HEALTH)) {
            erro.escrever(resposta, HttpServletResponse.SC_FORBIDDEN,
                    ErroApi.de("HTTPS_OBRIGATORIO", "Conexão não segura. Use HTTPS."));
            return;
        }
        cadeia.doFilter(requisicao, resposta);
    }
}
