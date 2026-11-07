package br.dev.azimute.comum;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base de todo erro de domínio. A camada web ({@code plataforma}) traduz o {@link Tipo} em status HTTP
 * e devolve {@code { erro, mensagem, detalhes }} (RF06).
 * <p>
 * {@code codigo} em SCREAMING_SNAKE, para o front decidir comportamento; {@code mensagem} em português,
 * para exibir.
 */
public abstract class ErroDeNegocio extends RuntimeException {

    public enum Tipo { VALIDACAO, NAO_ENCONTRADO, CONFLITO, NAO_AUTENTICADO, BLOQUEADO }

    private final String codigo;
    private final Tipo tipo;
    private final Map<String, Object> detalhes;

    protected ErroDeNegocio(String codigo, String mensagem, Tipo tipo, Map<String, Object> detalhes) {
        super(mensagem);
        this.codigo = Objects.requireNonNull(codigo, "codigo");
        this.tipo = Objects.requireNonNull(tipo, "tipo");
        this.detalhes = detalhes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(detalhes));
    }

    protected ErroDeNegocio(String codigo, String mensagem, Tipo tipo) {
        this(codigo, mensagem, tipo, Map.of());
    }

    public String codigo() {
        return codigo;
    }

    public Tipo tipo() {
        return tipo;
    }

    public Map<String, Object> detalhes() {
        return detalhes;
    }
}
