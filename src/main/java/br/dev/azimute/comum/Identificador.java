package br.dev.azimute.comum;

import java.util.UUID;

/**
 * Contrato de todo ID de agregado. Cada módulo tem o seu record ({@code XxxId(UUID valor)}), com o UUID não nulo e
 * {@code toString} devolvendo o próprio UUID; {@code comum} só conhece o contrato, nunca o domínio.
 * <p>
 * ID criado no servidor é v7: pelo {@code DEFAULT uuidv7()} do banco ou por {@link Uuid7#novo()}. ID que nasce no
 * aparelho (PUT com o ID na URL) é aceito em qualquer versão.
 */
public interface Identificador {

    UUID valor();
}
