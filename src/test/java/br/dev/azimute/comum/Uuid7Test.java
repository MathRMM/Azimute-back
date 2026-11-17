package br.dev.azimute.comum;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** UUIDv7 gerado no servidor (RFC 9562): ordenável pela criação, como o {@code uuidv7()} do banco. */
class Uuid7Test {

    @Test
    void versao_7_e_variante_ietf() {
        var id = Uuid7.novo();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void os_48_bits_iniciais_sao_o_instante_em_milissegundos() {
        var antes = Instant.now().toEpochMilli();
        var id = Uuid7.novo();
        var depois = Instant.now().toEpochMilli();

        var milissegundos = id.getMostSignificantBits() >>> 16;
        assertThat(milissegundos).isBetween(antes, depois + 1);
    }

    @Test
    void ids_gerados_em_sequencia_sao_crescentes_mesmo_no_mesmo_milissegundo() {
        var ids = new ArrayList<UUID>();
        IntStream.range(0, 10_000).forEach(i -> ids.add(Uuid7.novo()));

        for (int i = 1; i < ids.size(); i++) {
            assertThat(ids.get(i)).isGreaterThan(ids.get(i - 1));
            // Mesma ordem que o Postgres usa: bytes sem sinal, que é a ordem do texto.
            assertThat(ids.get(i).toString()).isGreaterThan(ids.get(i - 1).toString());
        }
    }

    @Test
    void sem_colisao_em_100_mil_geracoes() {
        var ids = new HashSet<UUID>();
        IntStream.range(0, 100_000).forEach(i -> ids.add(Uuid7.novo()));
        assertThat(ids).hasSize(100_000);
    }

    @Test
    void sem_colisao_entre_threads() throws InterruptedException {
        var ids = java.util.concurrent.ConcurrentHashMap.<UUID>newKeySet();
        var threads = IntStream.range(0, 8)
                .mapToObj(t -> Thread.ofVirtual().start(() -> IntStream.range(0, 5_000).forEach(i -> ids.add(Uuid7.novo()))))
                .toList();
        for (var thread : threads) {
            thread.join();
        }
        assertThat(ids).hasSize(40_000);
    }
}
