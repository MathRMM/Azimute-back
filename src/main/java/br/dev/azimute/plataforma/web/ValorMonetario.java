package br.dev.azimute.plataforma.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valor em reais no corpo da requisição: no máximo 2 casas decimais e 17 dígitos inteiros, o mesmo limite do
 * {@code NUMERIC(19, 2)} do banco. Nunca arredonda: 3 casas é erro. Aceita qualquer sinal; as variantes
 * {@link Positivo} e {@link NaoNegativo} acrescentam o sinal. Cada regra que falha sai com a sua mensagem.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@Digits(integer = 17, fraction = 2, message = ValorMonetario.MENSAGEM)
public @interface ValorMonetario {

    String MENSAGEM = "deve ter até 2 casas decimais";

    String message() default MENSAGEM;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Maior que zero. */
    @Documented
    @Target({ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = {})
    @Positive
    @Digits(integer = 17, fraction = 2, message = ValorMonetario.MENSAGEM)
    @interface Positivo {

        String message() default MENSAGEM;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** Zero ou mais. */
    @Documented
    @Target({ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = {})
    @PositiveOrZero
    @Digits(integer = 17, fraction = 2, message = ValorMonetario.MENSAGEM)
    @interface NaoNegativo {

        String message() default MENSAGEM;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }
}
