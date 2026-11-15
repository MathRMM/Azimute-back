package br.dev.azimute.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Lê {@code Authorization: Bearer <token>}, calcula o SHA-256 e busca o dispositivo ativo (RF32, RNF08).
 * Sem token válido, não autentica: a cadeia de segurança responde 401 adiante.
 * <p>
 * Não é bean de propósito: registrado só dentro da cadeia de segurança, nunca como filtro de servlet avulso.
 */
final class TokenFilter extends OncePerRequestFilter {

    private static final String PREFIXO = "Bearer ";

    private final DispositivoRepository dispositivos;

    TokenFilter(DispositivoRepository dispositivos) {
        this.dispositivos = dispositivos;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        var cabecalho = requisicao.getHeader(HttpHeaders.AUTHORIZATION);
        if (cabecalho != null && cabecalho.startsWith(PREFIXO) && cabecalho.length() > PREFIXO.length()) {
            var token = cabecalho.substring(PREFIXO.length()).strip();
            dispositivos.buscarAtivoPorHash(GeradorDeToken.hash(token)).ifPresent(dispositivo -> {
                var autenticacao = new UsernamePasswordAuthenticationToken(dispositivo, null, List.of());
                var contexto = SecurityContextHolder.createEmptyContext();
                contexto.setAuthentication(autenticacao);
                SecurityContextHolder.setContext(contexto);
                dispositivos.registrarUso(dispositivo.id());
            });
        }
        cadeia.doFilter(requisicao, resposta);
    }
}
