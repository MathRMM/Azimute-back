package br.dev.azimute.auth;

import br.dev.azimute.ClienteDeTeste;
import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Padrão de datas nas tabelas do auth (RF42, RF43 do PRD de contas): {@code created_at} e {@code updated_at}, sem
 * {@code deleted_at}. A revogação continua definitiva e o uso do token não conta como alteração.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class DatasDeControleIT {

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired UsuarioRepository usuarios;

    @Test
    void revogar_atualiza_updated_at_e_continua_definitivo() throws Exception {
        var mvc = ClienteDeTeste.autenticado(contexto, jdbc);
        ClienteDeTeste.novoDispositivo(jdbc, "Tablet");
        var id = jdbc.sql("SELECT id FROM auth.dispositivos WHERE nome = 'Tablet' AND revogado_em IS NULL LIMIT 1")
                .query(UUID.class).single();
        var antes = updatedAt("auth.dispositivos", id);

        mvc.perform(delete("/api/auth/dispositivos/" + id)).andExpect(status().isNoContent());

        assertThat(updatedAt("auth.dispositivos", id)).isAfter(antes);
        var revogado = jdbc.sql("SELECT revogado_em IS NOT NULL FROM auth.dispositivos WHERE id = :id")
                .param("id", id).query(Boolean.class).single();
        assertThat(revogado).isTrue();
    }

    @Test
    void usar_o_token_nao_muda_updated_at() throws Exception {
        var token = ClienteDeTeste.novoDispositivo(jdbc, "Celular " + UUID.randomUUID());
        var id = jdbc.sql("SELECT id FROM auth.dispositivos WHERE token_hash = :h")
                .param("h", ClienteDeTeste.sha256(token)).query(UUID.class).single();
        var antes = updatedAt("auth.dispositivos", id);

        ClienteDeTeste.autenticadoCom(contexto, token).perform(get("/api/auth/dispositivos"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].criadoEm").exists());

        var usado = jdbc.sql("SELECT ultimo_uso IS NOT NULL FROM auth.dispositivos WHERE id = :id")
                .param("id", id).query(Boolean.class).single();
        assertThat(usado).isTrue();
        assertThat(updatedAt("auth.dispositivos", id)).isEqualTo(antes);
    }

    @Test
    void trocar_a_senha_atualiza_updated_at_do_usuario() {
        var usuario = usuarios.buscarUnico();
        var antes = updatedAt("auth.usuarios", usuario.id());

        usuarios.atualizarSenha(usuario.id(), usuario.senhaHash());

        assertThat(updatedAt("auth.usuarios", usuario.id())).isAfter(antes);
    }

    @Test
    void colunas_seguem_o_padrao_sem_deleted_at() {
        assertThat(colunas("usuarios")).contains("created_at", "updated_at").doesNotContain("criado_em", "deleted_at");
        assertThat(colunas("dispositivos")).contains("created_at", "updated_at", "revogado_em")
                .doesNotContain("criado_em", "deleted_at");
        assertThat(colunas("tentativas_login")).contains("created_at").doesNotContain("em", "updated_at");
    }

    private OffsetDateTime updatedAt(String tabela, UUID id) {
        return jdbc.sql("SELECT updated_at FROM " + tabela + " WHERE id = :id").param("id", id)
                .query(OffsetDateTime.class).single();
    }

    private java.util.List<String> colunas(String tabela) {
        return jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'auth' "
                        + "AND table_name = :t").param("t", tabela).query(String.class).list();
    }
}
