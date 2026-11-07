package br.dev.azimute.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class DispositivoRepository {

    record DispositivoRow(DispositivoId id, String nome, Instant criadoEm, Instant ultimoUso) {}

    private final JdbcClient jdbc;

    DispositivoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    DispositivoId criar(String nome, String tokenHash) {
        var id = jdbc.sql("INSERT INTO auth.dispositivos (nome, token_hash) VALUES (:nome, :hash) RETURNING id")
                .param("nome", nome).param("hash", tokenHash)
                .query(UUID.class).single();
        return new DispositivoId(id);
    }

    /** Busca por índice único no hash, só entre os não revogados. */
    Optional<DispositivoAutenticado> buscarAtivoPorHash(String tokenHash) {
        return jdbc.sql("SELECT id, nome FROM auth.dispositivos WHERE token_hash = :hash AND revogado_em IS NULL")
                .param("hash", tokenHash)
                .query((rs, n) -> new DispositivoAutenticado(
                        new DispositivoId(rs.getObject("id", UUID.class)), rs.getString("nome")))
                .optional();
    }

    /** No máximo uma escrita por minuto por dispositivo: a condição está no próprio UPDATE (RF36). */
    void registrarUso(DispositivoId id) {
        jdbc.sql("""
                        UPDATE auth.dispositivos SET ultimo_uso = now()
                        WHERE id = :id AND (ultimo_uso IS NULL OR ultimo_uso < now() - INTERVAL '1 minute')""")
                .param("id", id.valor()).update();
    }

    List<DispositivoRow> listarAtivos() {
        return jdbc.sql("""
                        SELECT id, nome, criado_em, ultimo_uso FROM auth.dispositivos
                        WHERE revogado_em IS NULL ORDER BY criado_em""")
                .query((rs, n) -> new DispositivoRow(
                        new DispositivoId(rs.getObject("id", UUID.class)),
                        rs.getString("nome"),
                        rs.getTimestamp("criado_em").toInstant(),
                        instante(rs.getTimestamp("ultimo_uso"))))
                .list();
    }

    /** Idempotente: revogar o já revogado ou o inexistente não muda nada. */
    void revogar(DispositivoId id) {
        jdbc.sql("UPDATE auth.dispositivos SET revogado_em = now() WHERE id = :id AND revogado_em IS NULL")
                .param("id", id.valor()).update();
    }

    private static Instant instante(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
