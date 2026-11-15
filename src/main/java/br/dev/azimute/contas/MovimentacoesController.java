package br.dev.azimute.contas;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Movimentações e extrato (SPEC de contas §6.4). Exige token, como tudo sob {@code /api} (SEC01). */
@RestController
@RequestMapping("/api/contas")
class MovimentacoesController {

    private final MovimentacaoService movimentacoes;

    MovimentacoesController(MovimentacaoService movimentacoes) {
        this.movimentacoes = movimentacoes;
    }

    /** PUT com ID do cliente: o reenvio pela fila offline não duplica (P1). */
    @PutMapping("/movimentacoes/{id}")
    MovimentacaoResponse registrar(@PathVariable UUID id, @Valid @RequestBody RegistrarMovimentacaoRequest corpo) {
        return MovimentacaoResponse.de(movimentacoes.registrar(new MovimentacaoId(id), corpo.paraDominio()));
    }

    @DeleteMapping("/movimentacoes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void excluir(@PathVariable UUID id) {
        movimentacoes.excluir(new MovimentacaoId(id));
    }

    @PostMapping("/movimentacoes/{id}/restaurar")
    MovimentacaoResponse restaurar(@PathVariable UUID id) {
        return MovimentacaoResponse.de(movimentacoes.restaurar(new MovimentacaoId(id)));
    }

    @GetMapping("/{id}/extrato")
    ExtratoResponse extrato(@PathVariable UUID id,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return ExtratoResponse.de(movimentacoes.extrato(new ContaId(id), de, ate));
    }
}
