package br.dev.azimute.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Token opaco de 256 bits e seu hash.
 * <p>
 * SHA-256 e não BCrypt: o token tem 256 bits aleatórios, então não há o que adivinhar por força bruta; BCrypt
 * existe para proteger segredos fracos (senhas). SHA-256 permite buscar o token por índice único.
 */
final class GeradorDeToken {

    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final int BYTES = 32;

    private GeradorDeToken() {}

    static String novo() {
        var bytes = new byte[BYTES];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
