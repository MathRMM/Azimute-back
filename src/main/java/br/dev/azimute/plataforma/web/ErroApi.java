package br.dev.azimute.plataforma.web;

import java.util.Map;

/**
 * Formato único de erro da API (RF06).
 *
 * @param erro     código em SCREAMING_SNAKE, para o front decidir comportamento
 * @param mensagem texto em português, para exibir
 * @param detalhes dados estruturados do erro; objeto vazio quando não houver
 */
public record ErroApi(String erro, String mensagem, Map<String, Object> detalhes) {

    public ErroApi {
        detalhes = detalhes == null ? Map.of() : detalhes;
    }

    public static ErroApi de(String erro, String mensagem) {
        return new ErroApi(erro, mensagem, Map.of());
    }
}
