package br.dev.azimute;

import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Cliente HTTP dos testes de integração: um dispositivo já cadastrado (token gravado direto no banco, sem
 * passar pelo BCrypt nem pelo limite de tentativas) e conexão HTTPS, que é o padrão exigido sem perfil.
 */
public final class ClienteDeTeste {

    private ClienteDeTeste() {}

    public static MockMvc autenticado(WebApplicationContext contexto, JdbcClient jdbc) {
        return autenticadoCom(contexto, novoDispositivo(jdbc, "Dispositivo de teste"));
    }

    public static MockMvc autenticadoCom(WebApplicationContext contexto, String token) {
        return MockMvcBuilders.webAppContextSetup(contexto)
                .apply(springSecurity())
                .defaultRequest(get("/").secure(true).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .build();
    }

    /** Sem token, mas por HTTPS. */
    public static MockMvc anonimo(WebApplicationContext contexto) {
        return MockMvcBuilders.webAppContextSetup(contexto)
                .apply(springSecurity())
                .defaultRequest(get("/").secure(true))
                .build();
    }

    /** Grava um dispositivo ativo e devolve o token em texto (que o banco nunca guarda). */
    public static String novoDispositivo(JdbcClient jdbc, String nome) {
        var token = "token-de-teste-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO auth.dispositivos (nome, token_hash) VALUES (:nome, :hash)")
                .param("nome", nome)
                .param("hash", sha256(token))
                .update();
        return token;
    }

    public static String sha256(String texto) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
