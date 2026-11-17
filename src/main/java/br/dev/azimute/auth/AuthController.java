package br.dev.azimute.auth;

import br.dev.azimute.comum.Fuso;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    record CadastrarDispositivoRequest(@NotBlank String login, @NotBlank String senha,
                                       @NotBlank @Size(max = 100) String nomeDispositivo) {}

    record TrocarSenhaRequest(@NotBlank String senhaAtual, @NotNull String senhaNova) {}

    record CadastroResponse(UUID id, String token) {}

    record DispositivoResponse(UUID id, String nome, OffsetDateTime criadoEm, OffsetDateTime ultimoUso, boolean atual) {}


    private final DispositivoService servico;
    private final SenhaService senhas;

    AuthController(DispositivoService servico, SenhaService senhas) {
        this.servico = servico;
        this.senhas = senhas;
    }

    /** Público: é aqui que um aparelho novo troca a senha por um token (RF31). */
    @PostMapping("/dispositivos")
    CadastroResponse cadastrar(@Valid @RequestBody CadastrarDispositivoRequest corpo, HttpServletRequest requisicao) {
        // No prod, getRemoteAddr() já reflete o X-Forwarded-For tratado pelo framework (forward-headers-strategy).
        var cadastro = servico.cadastrar(corpo.login(), corpo.senha(), corpo.nomeDispositivo(),
                requisicao.getRemoteAddr());
        return new CadastroResponse(cadastro.id().valor(), cadastro.token());
    }

    @GetMapping("/dispositivos")
    List<DispositivoResponse> listar(@AuthenticationPrincipal DispositivoAutenticado atual) {
        return servico.listar().stream()
                .map(d -> new DispositivoResponse(d.id().valor(), d.nome(), Fuso.naApi(d.criadoEm()), Fuso.naApi(d.ultimoUso()),
                        d.id().equals(atual.id())))
                .toList();
    }

    /** Revoga todos os outros dispositivos; o desta requisição continua (RF35). */
    @PostMapping("/senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void trocarSenha(@Valid @RequestBody TrocarSenhaRequest corpo, @AuthenticationPrincipal DispositivoAutenticado atual) {
        senhas.trocar(atual, corpo.senhaAtual(), corpo.senhaNova());
    }

    @DeleteMapping("/dispositivos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revogar(@PathVariable UUID id) {
        servico.revogar(new DispositivoId(id));
    }
}
