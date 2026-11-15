package br.dev.azimute.comum;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Objects;
import java.util.Set;

/**
 * Dia útil bancário: nem fim de semana nem feriado nacional (P5 e T4 do SPEC do fluxo). Feriado estadual ou
 * municipal fica de fora: não fecha os bancos do país todo.
 * <p>
 * Os feriados móveis saem da Páscoa: Carnaval (segunda e terça, 48 e 47 dias antes), Sexta-feira Santa (2 dias
 * antes) e Corpus Christi (60 dias depois).
 */
public final class DiasUteis {

    private static final Set<MonthDay> FIXOS = Set.of(
            MonthDay.of(1, 1),   // Confraternização Universal
            MonthDay.of(4, 21),  // Tiradentes
            MonthDay.of(5, 1),   // Dia do Trabalho
            MonthDay.of(9, 7),   // Independência
            MonthDay.of(10, 12), // Nossa Senhora Aparecida
            MonthDay.of(11, 2),  // Finados
            MonthDay.of(11, 15), // Proclamação da República
            MonthDay.of(11, 20), // Consciência Negra
            MonthDay.of(12, 25)); // Natal

    private DiasUteis() {
    }

    public static boolean ehDiaUtil(LocalDate data) {
        Objects.requireNonNull(data, "data");
        var dia = data.getDayOfWeek();
        return dia != DayOfWeek.SATURDAY && dia != DayOfWeek.SUNDAY && !ehFeriado(data);
    }

    /** A própria data, se for útil; senão o dia útil mais próximo antes dela (RN18 do PRD do fluxo). */
    public static LocalDate diaUtilAnteriorOuMesmo(LocalDate data) {
        Objects.requireNonNull(data, "data");
        var dia = data;
        while (!ehDiaUtil(dia)) {
            dia = dia.minusDays(1);
        }
        return dia;
    }

    private static boolean ehFeriado(LocalDate data) {
        if (FIXOS.contains(MonthDay.from(data))) {
            return true;
        }
        var pascoa = pascoa(data.getYear());
        return data.equals(pascoa.minusDays(48)) || data.equals(pascoa.minusDays(47))
                || data.equals(pascoa.minusDays(2)) || data.equals(pascoa.plusDays(60));
    }

    /** Domingo de Páscoa do calendário gregoriano, pelo algoritmo de Meeus/Jones/Butcher. */
    static LocalDate pascoa(int ano) {
        int a = ano % 19;
        int b = ano / 100;
        int c = ano % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int mes = (h + l - 7 * m + 114) / 31;
        int dia = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(ano, mes, dia);
    }
}
