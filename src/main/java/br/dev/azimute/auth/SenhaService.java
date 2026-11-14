package br.dev.azimute.auth;

import br.dev.azimute.comum.ErroDeNegocio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Troca de senha (RF35, RN15). Quem troca a senha suspeita de vazamento: todos os outros aparelhos perdem o
 * acesso na mesma transação, e só o que fez a troca continua.
 */
@Service
class SenhaService {

    private static final Logger log = LoggerFactory.getLogger(SenhaService.class);

    private final UsuarioRepository usuarios;
    private final DispositivoRepository dispositivos;
    private final PasswordEncoder encoder;

    SenhaService(UsuarioRepository usuarios, DispositivoRepository dispositivos, PasswordEncoder encoder) {
        this.usuarios = usuarios;
        this.dispositivos = dispositivos;
        this.encoder = encoder;
    }

    @Transactional
    void trocar(DispositivoAutenticado atual, String senhaAtual, String senhaNova) {
        var usuario = usuarios.buscarUnico();
        if (!encoder.matches(senhaAtual, usuario.senhaHash())) {
            log.warn("Troca de senha recusada: senha atual errada (dispositivo {})", atual.id().valor());
            throw new CredenciaisInvalidasException();
        }
        if (senhaNova.length() < SeedUsuario.TAMANHO_MINIMO_SENHA) {
            throw new SenhaCurtaException();
        }
        usuarios.atualizarSenha(usuario.id(), encoder.encode(senhaNova));
        var revogados = dispositivos.revogarTodosExceto(atual.id());
        log.warn("Senha trocada pelo dispositivo {}; {} outro(s) dispositivo(s) revogado(s)", atual.id().valor(), revogados);
    }

    static final class SenhaCurtaException extends ErroDeNegocio {
        SenhaCurtaException() {
            super("SENHA_CURTA", "A senha nova precisa ter pelo menos " + SeedUsuario.TAMANHO_MINIMO_SENHA + " caracteres",
                    Tipo.VALIDACAO, Map.of("minimo", SeedUsuario.TAMANHO_MINIMO_SENHA));
        }
    }
}
