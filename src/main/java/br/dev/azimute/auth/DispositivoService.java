package br.dev.azimute.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Senha uma vez, token por dispositivo depois (01-modulo-auth, Decisão). */
@Service
class DispositivoService {

    record Cadastro(DispositivoId id, String token) {}

    private final UsuarioRepository usuarios;
    private final DispositivoRepository dispositivos;
    private final PasswordEncoder encoder;

    DispositivoService(UsuarioRepository usuarios, DispositivoRepository dispositivos, PasswordEncoder encoder) {
        this.usuarios = usuarios;
        this.dispositivos = dispositivos;
        this.encoder = encoder;
    }

    /** Valida a senha (BCrypt) e devolve o token uma única vez; o banco guarda só o hash (RF31, SEC02). */
    @Transactional
    Cadastro cadastrar(String login, String senha, String nomeDispositivo) {
        var usuario = usuarios.buscarPorLogin(login)
                .filter(u -> encoder.matches(senha, u.senhaHash()))
                .orElseThrow(CredenciaisInvalidasException::new);

        var token = GeradorDeToken.novo();
        var id = dispositivos.criar(nomeDispositivo.strip(), GeradorDeToken.hash(token));
        usuarios.registrarAcesso(usuario.id());
        return new Cadastro(id, token);
    }

    List<DispositivoRepository.DispositivoRow> listar() {
        return dispositivos.listarAtivos();
    }

    void revogar(DispositivoId id) {
        dispositivos.revogar(id);
    }
}
