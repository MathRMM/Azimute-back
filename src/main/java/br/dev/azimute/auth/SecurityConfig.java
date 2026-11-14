package br.dev.azimute.auth;

import br.dev.azimute.plataforma.web.ErroApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Cadeia de segurança da API (01-modulo-auth, Configuração).
 * <p>
 * CSRF desligado é seguro <b>apenas</b> porque a API é stateless e não usa cookie de sessão: com token no
 * header, não há credencial que o navegador anexe sozinho. Se um dia houver sessão por cookie, o CSRF volta.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain cadeiaDeSeguranca(HttpSecurity http, DispositivoRepository dispositivos,
                                          JsonMapper json, Environment ambiente) throws Exception {
        var erro = new RespostaDeErro(json);
        var exigirHttps = !ambiente.acceptsProfiles(Profiles.of("dev"));

        return http
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpsObrigatorioFilter.HEALTH).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/dispositivos").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterAfter(new HttpsObrigatorioFilter(exigirHttps, erro), HeaderWriterFilter.class)
                .addFilterBefore(new TokenFilter(dispositivos), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, resp, ex) -> {
                            resp.setHeader("WWW-Authenticate", "Bearer");
                            erro.escrever(resp, 401, ErroApi.de("NAO_AUTENTICADO",
                                    "Autenticação necessária. Cadastre este dispositivo para obter um token."));
                        })
                        .accessDeniedHandler((req, resp, ex) ->
                                erro.escrever(resp, 403, ErroApi.de("ACESSO_NEGADO", "Acesso negado"))))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000)))
                .build();
    }

    /** Relógio do limite de tentativas; os testes trocam por um ajustável. */
    @Bean
    Clock relogio() {
        return Clock.systemDefaultZone();
    }

    /** Custo 12: ~250 ms por verificação, lento de propósito. Roda só no cadastro de um aparelho novo. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
