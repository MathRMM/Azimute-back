package br.dev.azimute.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    record CadastrarDispositivoRequest(@NotBlank String login, @NotBlank String senha,
                                       @NotBlank @Size(max = 100) String nomeDispositivo) {}

    record CadastroResponse(UUID id, String token) {}

    record DispositivoResponse(UUID id, String nome, OffsetDateTime criadoEm, OffsetDateTime ultimoUso, boolean atual) {}

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    private final DispositivoService servico;

    AuthController(DispositivoService servico) {
        this.servico = servico;
    }

    /** Público: é aqui que um aparelho novo troca a senha por um token (RF31). */
    @PostMapping("/dispositivos")
    CadastroResponse cadastrar(@Valid @RequestBody CadastrarDispositivoRequest corpo) {
        var cadastro = servico.cadastrar(corpo.login(), corpo.senha(), corpo.nomeDispositivo());
        return new CadastroResponse(cadastro.id().valor(), cadastro.token());
    }

    @GetMapping("/dispositivos")
    List<DispositivoResponse> listar(@AuthenticationPrincipal DispositivoAutenticado atual) {
        return servico.listar().stream()
                .map(d -> new DispositivoResponse(d.id().valor(), d.nome(), noFuso(d.criadoEm()), noFuso(d.ultimoUso()),
                        d.id().equals(atual.id())))
                .toList();
    }

    private static OffsetDateTime noFuso(Instant instante) {
        return instante == null ? null : instante.atZone(FUSO).toOffsetDateTime();
    }

    @DeleteMapping("/dispositivos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revogar(@PathVariable UUID id) {
        servico.revogar(new DispositivoId(id));
    }
}
