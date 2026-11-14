package br.dev.azimute.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Senha uma vez, token por dispositivo depois (01-modulo-auth, Decisão). */
@Service
class DispositivoService {

    record Cadastro(DispositivoId id, String token) {}

    private static final Logger log = LoggerFactory.getLogger(DispositivoService.class);

    private final UsuarioRepository usuarios;
    private final DispositivoRepository dispositivos;
    private final PasswordEncoder encoder;
    private final LimiteDeTentativas limite;
    /** Hash de uma senha que ninguém conhece: login inexistente paga o mesmo BCrypt que a senha errada (RN18). */
    private final String hashFicticio;

    DispositivoService(UsuarioRepository usuarios, DispositivoRepository dispositivos, PasswordEncoder encoder,
                       LimiteDeTentativas limite) {
        this.usuarios = usuarios;
        this.dispositivos = dispositivos;
        this.encoder = encoder;
        this.limite = limite;
        this.hashFicticio = encoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Verifica o limite, valida a senha (BCrypt) e devolve o token uma única vez; o banco guarda só o hash
     * (RF31, RF37, SEC02). A falha também é registrada: por isso ela não desfaz a transação.
     */
    @Transactional(noRollbackFor = CredenciaisInvalidasException.class)
    Cadastro cadastrar(String login, String senha, String nomeDispositivo, String ip) {
        limite.verificar(ip);

        var usuario = usuarios.buscarPorLogin(login);
        var confere = encoder.matches(senha, usuario.map(UsuarioRepository.UsuarioRow::senhaHash).orElse(hashFicticio));
        limite.registrar(ip, confere && usuario.isPresent());
        if (!confere || usuario.isEmpty()) {
            log.warn("Falha no cadastro de dispositivo (IP {})", ip);
            throw new CredenciaisInvalidasException();
        }

        var token = GeradorDeToken.novo();
        var id = dispositivos.criar(nomeDispositivo.strip(), GeradorDeToken.hash(token));
        usuarios.registrarAcesso(usuario.get().id());
        log.info("Dispositivo '{}' cadastrado (IP {})", nomeDispositivo.strip(), ip);
        return new Cadastro(id, token);
    }

    List<DispositivoRepository.DispositivoRow> listar() {
        return dispositivos.listarAtivos();
    }

    void revogar(DispositivoId id) {
        dispositivos.revogar(id);
    }
}
