package br.dev.azimute.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class UsuarioRepository {

    record UsuarioRow(UUID id, String login, String senhaHash) {}

    private final JdbcClient jdbc;

    UsuarioRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean existeAlgum() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM auth.usuarios)").query(Boolean.class).single();
    }

    Optional<UsuarioRow> buscarPorLogin(String login) {
        return jdbc.sql("SELECT id, login, senha_hash FROM auth.usuarios WHERE login = :login")
                .param("login", login)
                .query((rs, n) -> new UsuarioRow(rs.getObject("id", UUID.class), rs.getString("login"),
                        rs.getString("senha_hash")))
                .optional();
    }

    /** Usuário único: o sistema é pessoal (RN19). */
    UsuarioRow buscarUnico() {
        return jdbc.sql("SELECT id, login, senha_hash FROM auth.usuarios")
                .query((rs, n) -> new UsuarioRow(rs.getObject("id", UUID.class), rs.getString("login"),
                        rs.getString("senha_hash")))
                .single();
    }

    void atualizarSenha(UUID id, String senhaHash) {
        jdbc.sql("UPDATE auth.usuarios SET senha_hash = :hash, updated_at = now() WHERE id = :id")
                .param("hash", senhaHash).param("id", id).update();
    }

    void criar(String login, String senhaHash) {
        jdbc.sql("INSERT INTO auth.usuarios (login, senha_hash) VALUES (:login, :hash)")
                .param("login", login).param("hash", senhaHash).update();
    }

    void registrarAcesso(UUID id) {
        jdbc.sql("UPDATE auth.usuarios SET ultimo_acesso = now() WHERE id = :id").param("id", id).update();
    }
}
