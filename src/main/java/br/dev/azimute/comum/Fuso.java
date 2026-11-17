package br.dev.azimute.comum;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * O fuso único do sistema (RNF05). No domínio e no banco o instante é {@link Instant}; na API ele sai como
 * {@link OffsetDateTime} neste fuso, e a conversão fica só aqui.
 */
public final class Fuso {

    public static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private Fuso() {}

    /** O instante como a API devolve. Nulo continua nulo. */
    public static OffsetDateTime naApi(Instant instante) {
        return instante == null ? null : instante.atZone(ZONA).toOffsetDateTime();
    }

    /** A data de hoje no fuso do sistema, mesmo com o servidor em UTC. */
    public static LocalDate hoje(Clock relogio) {
        return LocalDate.now(relogio.withZone(ZONA));
    }
}
