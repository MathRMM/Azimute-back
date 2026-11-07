package br.dev.azimute.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * PROVISÓRIO até a Fase 7 do SPEC: libera tudo e impede o usuário/senha gerados pelo Boot.
 * O backend só roda localmente até lá (PRD, Fora do Escopo). Será substituído por SecurityConfig.
 */
@Configuration(proxyBeanMethods = false)
class SegurancaProvisoriaConfig {

    @Bean
    SecurityFilterChain filtroProvisorio(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }
}
