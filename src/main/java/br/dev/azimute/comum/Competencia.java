package br.dev.azimute.comum;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Mês de referência do domínio financeiro (RF02, RN03).
 * <p>
 * Na API, sempre {@code yyyy-MM}. No banco, {@code DATE} com dia 1 e
 * {@code CHECK (EXTRACT(DAY FROM coluna) = 1)}.
 */
public record Competencia(YearMonth mes) implements Comparable<Competencia> {

    public static final String FORMATO = "yyyy-MM";

    private static final Pattern PADRAO = Pattern.compile("\\d{4}-\\d{2}");
    private static final DateTimeFormatter FORMATADOR =
            DateTimeFormatter.ofPattern("uuuu-MM").withResolverStyle(ResolverStyle.STRICT);

    public Competencia {
        Objects.requireNonNull(mes, "mes");
    }

    /** @throws IllegalArgumentException se o texto não estiver exatamente em {@code yyyy-MM} */
    public static Competencia de(String texto) {
        if (texto == null || !PADRAO.matcher(texto).matches()) {
            throw new IllegalArgumentException("Competência deve estar no formato " + FORMATO + ": " + texto);
        }
        try {
            return new Competencia(YearMonth.parse(texto, FORMATADOR));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Competência inválida (" + FORMATO + "): " + texto, e);
        }
    }

    /** A competência do mês de uma data; no banco, a coluna {@code DATE} com dia 1. */
    public static Competencia doDia(LocalDate dia) {
        return new Competencia(YearMonth.from(Objects.requireNonNull(dia, "dia")));
    }

    /** O dia 1 do mês, como o banco guarda. */
    public LocalDate primeiroDia() {
        return mes.atDay(1);
    }

    public Competencia proxima() {
        return mais(1);
    }

    public Competencia mais(int meses) {
        return new Competencia(mes.plusMonths(meses));
    }

    /** Do mês atual até {@code fim}, inclusive. Vazio se {@code fim} for anterior. */
    public Stream<Competencia> ate(Competencia fim) {
        return Stream.iterate(this, c -> c.compareTo(fim) <= 0, Competencia::proxima);
    }

    @Override
    public int compareTo(Competencia outra) {
        return mes.compareTo(outra.mes);
    }

    @Override
    public String toString() {
        return mes.format(FORMATADOR);
    }
}
