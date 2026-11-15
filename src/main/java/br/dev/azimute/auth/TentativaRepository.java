package br.dev.azimute.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Tentativas de cadastro de dispositivo, com IP, horário e resultado (RF37, SEC08). */
@Repository
class TentativaRepository {

    private final JdbcClient jdbc;

    TentativaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void registrar(String ip, Instant em, boolean sucesso) {
        jdbc.sql("INSERT INTO auth.tentativas_login (ip, em, sucesso) VALUES (CAST(:ip AS inet), :em, :sucesso)")
                .param("ip", ip).param("em", Timestamp.from(em)).param("sucesso", sucesso)
                .update();
    }

    /**
     * Horário da {@code n}-ésima falha mais recente do IP desde {@code desde}, se houver ao menos {@code n}.
     * Enquanto ela estiver na janela, o IP tem {@code n} falhas ou mais; quando sair, o bloqueio acaba.
     */
    Optional<Instant> enesimaFalhaDoIp(String ip, Instant desde, Instant ate, int n) {
        return jdbc.sql("""
                        SELECT em FROM auth.tentativas_login
                        WHERE ip = CAST(:ip AS inet) AND NOT sucesso AND em > :desde AND em <= :ate
                        ORDER BY em DESC OFFSET :pular LIMIT 1""")
                .param("ip", ip).param("desde", Timestamp.from(desde)).param("ate", Timestamp.from(ate))
                .param("pular", n - 1)
                .query(Timestamp.class).optional().map(Timestamp::toInstant);
    }

    long falhasGlobais(Instant desde, Instant ate) {
        return jdbc.sql("""
                        SELECT count(*) FROM auth.tentativas_login
                        WHERE NOT sucesso AND em > :desde AND em <= :ate""")
                .param("desde", Timestamp.from(desde)).param("ate", Timestamp.from(ate))
                .query(Long.class).single();
    }
}
