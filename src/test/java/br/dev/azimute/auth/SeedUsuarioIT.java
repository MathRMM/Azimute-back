package br.dev.azimute.auth;

import br.dev.azimute.AzimuteApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Primeiro acesso (RF30, SEC04). Sobe a aplicação de verdade contra um banco vazio: sem senha padrão, a
 * subida falha — num servidor público, senha padrão dura minutos. "O mais importante e o mais esquecido."
 */
class SeedUsuarioIT {

    static PostgreSQLContainer banco;

    @BeforeAll
    static void subirBanco() {
        banco = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        banco.start();
    }

    @AfterAll
    static void derrubarBanco() {
        banco.stop();
    }

    /** Banco limpo a cada teste: o seed depende de existir ou não usuário. */
    @BeforeEach
    void apagarUsuarios() throws Exception {
        try (var conexao = DriverManager.getConnection(banco.getJdbcUrl(), banco.getUsername(), banco.getPassword());
             var comando = conexao.createStatement()) {
            comando.execute("DO $$ BEGIN IF to_regclass('auth.usuarios') IS NOT NULL THEN DELETE FROM auth.usuarios; END IF; END $$");
        }
    }

    /** Argumentos de linha de comando: precedência maior que o application.yml de teste. */
    static ConfigurableApplicationContext subir(String senhaInicial) {
        return new SpringApplicationBuilder(AzimuteApplication.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.port=0",
                        "--spring.datasource.url=" + banco.getJdbcUrl(),
                        "--spring.datasource.username=" + banco.getUsername(),
                        "--spring.datasource.password=" + banco.getPassword(),
                        "--app.senha-inicial=" + senhaInicial);
    }

    @Test
    void sem_usuario_e_sem_senha_inicial_a_aplicacao_nao_sobe() {
        assertThatThrownBy(() -> subir("").close())
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("APP_SENHA_INICIAL não definida");
    }

    @Test
    void senha_inicial_curta_impede_a_subida() {
        assertThatThrownBy(() -> subir("onze-chars!").close())
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("pelo menos 12 caracteres");
    }

    @Test
    void cria_admin_com_bcrypt_e_depois_sobe_sem_a_variavel() {
        try (var contexto = subir("senha-inicial-bem-comprida")) {
            var jdbc = contexto.getBean(JdbcClient.class);
            var hash = jdbc.sql("SELECT senha_hash FROM auth.usuarios WHERE login = 'admin'").query(String.class).single();
            assertThat(hash).startsWith("$2").contains("$12$").doesNotContain("senha-inicial-bem-comprida");
        }
        try (var contexto = subir("")) {
            var jdbc = contexto.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT count(*) FROM auth.usuarios").query(Long.class).single()).isEqualTo(1);
        }
    }
}
