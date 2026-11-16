package br.dev.azimute.comum;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Gera UUIDv7 (RFC 9562) para os IDs criados no servidor, no mesmo formato do {@code uuidv7()} do banco: os 48 bits
 * iniciais são o instante em milissegundos, então o ID é ordenável pela criação e não fragmenta o índice (doc 00).
 * <p>
 * Dentro do mesmo milissegundo, os 74 bits aleatórios funcionam como contador (método 2 da RFC): o próximo ID é o
 * anterior + 1, e a sequência continua crescente. Se o relógio voltar, o último instante é mantido.
 */
public final class Uuid7 {

    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final long MASCARA_12 = 0xFFFL;
    private static final long MASCARA_62 = 0x3FFF_FFFF_FFFF_FFFFL;

    private static long ultimoMilissegundo;
    private static long ultimoA;
    private static long ultimoB;

    private Uuid7() {
    }

    public static synchronized UUID novo() {
        var agora = System.currentTimeMillis();
        if (agora > ultimoMilissegundo) {
            ultimoMilissegundo = agora;
            ultimoA = ALEATORIO.nextLong() & MASCARA_12;
            ultimoB = ALEATORIO.nextLong() & MASCARA_62;
        } else if (ultimoB < MASCARA_62) {
            ultimoB++;
        } else if (ultimoA < MASCARA_12) {
            ultimoA++;
            ultimoB = 0;
        } else {
            // 2^74 IDs no mesmo milissegundo: avança o instante em vez de repetir.
            ultimoMilissegundo++;
            ultimoA = ALEATORIO.nextLong() & MASCARA_12;
            ultimoB = ALEATORIO.nextLong() & MASCARA_62;
        }
        var maisSignificativos = (ultimoMilissegundo << 16) | (0x7L << 12) | ultimoA;
        var menosSignificativos = (0b10L << 62) | ultimoB;
        return new UUID(maisSignificativos, menosSignificativos);
    }
}
