package br.dev.azimute.contas;

import br.dev.azimute.plataforma.web.ValorMonetario;
import br.dev.azimute.comum.Dinheiro;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Conciliações (SPEC de contas §6.4). Não há endpoint de alteração nem de exclusão: a conciliação é imutável
 * (RN07). Exige token, como tudo sob {@code /api} (SEC01).
 */
@RestController
@RequestMapping("/api/contas/{id}/conciliacoes")
class ConciliacoesController {

    /** O saldo real pode ser negativo; até 2 casas, sem arredondar. */
    record ConciliarRequest(@NotNull LocalDate data,
                            @NotNull @ValorMonetario BigDecimal saldoReal,
                            String justificativa) {}

    record ConciliacaoResponse(UUID id, LocalDate data, Dinheiro saldoReal, Dinheiro saldoCalculado,
                               Dinheiro divergencia, String justificativa) {
        static ConciliacaoResponse de(Conciliacao c) {
            return new ConciliacaoResponse(c.id().valor(), c.data(), c.saldoReal(), c.saldoCalculado(),
                    c.divergencia(), c.justificativa());
        }
    }

    private final ConciliacaoService conciliacoes;

    ConciliacoesController(ConciliacaoService conciliacoes) {
        this.conciliacoes = conciliacoes;
    }

    /** 201 para a conciliação nova; 200 para o reenvio idêntico da última (T4). */
    @PostMapping
    ResponseEntity<ConciliacaoResponse> conciliar(@PathVariable UUID id, @Valid @RequestBody ConciliarRequest corpo) {
        var resultado = conciliacoes.conciliar(new ContaId(id), corpo.data(), new Dinheiro(corpo.saldoReal()),
                corpo.justificativa());
        return ResponseEntity.status(resultado.criada() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ConciliacaoResponse.de(resultado.conciliacao()));
    }

    @GetMapping
    List<ConciliacaoResponse> historico(@PathVariable UUID id) {
        return conciliacoes.historico(new ContaId(id)).stream().map(ConciliacaoResponse::de).toList();
    }
}
