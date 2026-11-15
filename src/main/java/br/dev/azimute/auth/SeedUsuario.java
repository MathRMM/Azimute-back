package br.dev.azimute.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Cria o usuário inicial na subida, com a senha de {@code APP_SENHA_INICIAL} (RF30).
 * <p>
 * Falhar na subida quando não há usuário nem senha é intencional: a alternativa — subir com senha padrão — é
 * como vazamento acontece, e num servidor público isso dura minutos (SEC04).
 */
@Component
class SeedUsuario implements ApplicationRunner {

    static final String LOGIN = "admin";
    static final int TAMANHO_MINIMO_SENHA = 12;

    private static final Logger log = LoggerFactory.getLogger(SeedUsuario.class);

    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;
    private final String senhaInicial;

    SeedUsuario(UsuarioRepository usuarios, PasswordEncoder encoder,
                @Value("${app.senha-inicial:}") String senhaInicial) {
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.senhaInicial = senhaInicial;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (usuarios.existeAlgum()) {
            return;
        }
        if (senhaInicial == null || senhaInicial.isBlank()) {
            throw new IllegalStateException("APP_SENHA_INICIAL não definida e nenhum usuário existe");
        }
        if (senhaInicial.length() < TAMANHO_MINIMO_SENHA) {
            throw new IllegalStateException(
                    "APP_SENHA_INICIAL precisa ter pelo menos " + TAMANHO_MINIMO_SENHA + " caracteres");
        }
        usuarios.criar(LOGIN, encoder.encode(senhaInicial));
        log.warn("Usuário inicial '{}' criado. Remova APP_SENHA_INICIAL do ambiente.", LOGIN);
    }
}
