package br.dev.azimute.fluxo;

import br.dev.azimute.comum.CamposEditaveis;
import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ganhos avulsos (RF20–RF24). Exige token, como tudo sob {@code /api} (SEC01). */
@RestController
@RequestMapping("/api/ganhos")
class GanhosController {

    private static final String MES = "\\d{4}-(0[1-9]|1[0-2])";
    private static final CamposEditaveis CAMPOS_EDITAVEIS =
            new CamposEditaveis("competencia", "descricao", "tipo", "bruto", "liquido", "conta", "recebidoEm");

    /**
     * Valores positivos com até 2 casas, sem arredondar (RN03). O tipo entra como texto para que {@code SALARIO}
     * responda com o erro próprio (P4) e um tipo desconhecido, com o erro de campo.
     */
    record RegistrarGanhoRequest(
            @NotNull @Pattern(regexp = MES, message = "deve estar no formato AAAA-MM") String competencia,
            @NotBlank String descricao,
            @NotNull @Pattern(regexp = "SALARIO|PLR|FREELA|REEMBOLSO|OUTRO",
                    message = "deve ser PLR, FREELA, REEMBOLSO ou OUTRO") String tipo,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal bruto,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal liquido,
            @NotNull UUID conta,
            @NotNull LocalDate recebidoEm) {

        NovoGanho paraDominio() {
            return new NovoGanho(Competencia.de(competencia), descricao, TipoGanho.valueOf(tipo), new Dinheiro(bruto),
                    new Dinheiro(liquido), new ContaId(conta), recebidoEm);
        }
    }

    record RecebimentoResponse(UUID id, String papel, Dinheiro valor, LocalDate data, UUID conta) {
        static RecebimentoResponse de(Recebimento r) {
            return new RecebimentoResponse(r.id(), r.papel().name(), r.valor(), r.data(), r.conta().valor());
        }
    }

    /** Bruto, líquido, tipo e recebimentos (RF23). {@code holerite} só no ganho que nasceu dele. */
    record GanhoResponse(UUID id, Competencia competencia, String descricao, String tipo, Dinheiro bruto,
                         Dinheiro liquido, UUID holerite, List<RecebimentoResponse> recebimentos) {
        static GanhoResponse de(GanhoComRecebimentos item) {
            var g = item.ganho();
            return new GanhoResponse(g.id().valor(), g.competencia(), g.descricao(), g.tipo().name(), g.bruto(),
                    g.liquido(), g.holerite() == null ? null : g.holerite().valor(),
                    item.recebimentos().stream().map(RecebimentoResponse::de).toList());
        }
    }

    private final GanhoService ganhos;

    GanhosController(GanhoService ganhos) {
        this.ganhos = ganhos;
    }

    /** PUT com ID do cliente: o reenvio pela fila offline não duplica (RF20). */
    @PutMapping("/{id}")
    ResponseEntity<GanhoResponse> registrar(@PathVariable UUID id, @Valid @RequestBody RegistrarGanhoRequest corpo) {
        var resultado = ganhos.registrar(new GanhoId(id), corpo.paraDominio());
        return ResponseEntity.status(resultado.criado() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(GanhoResponse.de(resultado.ganho()));
    }

    /** Corpo como mapa, para nomear o campo proibido e distinguir ausente de inválido (RF22). */
    @PatchMapping("/{id}")
    GanhoResponse alterar(@PathVariable UUID id, @RequestBody Map<String, Object> corpo) {
        CAMPOS_EDITAVEIS.conferir(corpo, p -> new CampoNaoEditavelException(p,
                "competência, descrição, tipo, bruto, líquido, conta e data do recebimento"));
        return GanhoResponse.de(ganhos.alterar(new GanhoId(id), lerAlteracao(corpo)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void excluir(@PathVariable UUID id) {
        ganhos.excluir(new GanhoId(id));
    }

    @PostMapping("/{id}/restaurar")
    GanhoResponse restaurar(@PathVariable UUID id) {
        return GanhoResponse.de(ganhos.restaurar(new GanhoId(id)));
    }

    @GetMapping
    List<GanhoResponse> listar(@RequestParam String competencia) {
        Competencia mes;
        try {
            mes = Competencia.de(competencia);
        } catch (IllegalArgumentException e) {
            throw new CompetenciaInvalidaException(competencia);
        }
        return ganhos.listar(mes).stream().map(GanhoResponse::de).toList();
    }

    private static AlteracaoGanho lerAlteracao(Map<String, Object> corpo) {
        var erros = new LinkedHashMap<String, Object>();
        var competencia = competencia(corpo.get("competencia"), erros);
        var descricao = descricao(corpo.get("descricao"), erros);
        var tipo = tipo(corpo.get("tipo"), erros);
        var bruto = valor(corpo, "bruto", erros);
        var liquido = valor(corpo, "liquido", erros);
        var conta = conta(corpo.get("conta"), erros);
        var recebidoEm = data(corpo.get("recebidoEm"), erros);
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException(erros);
        }
        return new AlteracaoGanho(competencia, descricao, tipo, bruto, liquido, conta, recebidoEm);
    }

    private static Competencia competencia(Object valor, Map<String, Object> erros) {
        if (valor == null) {
            return null;
        }
        try {
            return Competencia.de(valor.toString());
        } catch (IllegalArgumentException e) {
            erros.put("competencia", "deve estar no formato AAAA-MM");
            return null;
        }
    }

    private static String descricao(Object valor, Map<String, Object> erros) {
        if (valor == null) {
            return null;
        }
        if (valor instanceof String texto && !texto.isBlank()) {
            return texto;
        }
        erros.put("descricao", "deve ser texto não vazio");
        return null;
    }

    private static TipoGanho tipo(Object valor, Map<String, Object> erros) {
        if (valor == null) {
            return null;
        }
        var validos = Arrays.stream(TipoGanho.values()).map(Enum::name).toList();
        if (validos.contains(valor.toString())) {
            return TipoGanho.valueOf(valor.toString());
        }
        erros.put("tipo", "deve ser PLR, FREELA, REEMBOLSO ou OUTRO");
        return null;
    }

    /** Mesmas regras do PUT: positivo e no máximo 2 casas, sem arredondar (RN03). */
    private static Dinheiro valor(Map<String, Object> corpo, String campo, Map<String, Object> erros) {
        var valor = corpo.get(campo);
        if (valor == null) {
            return null;
        }
        try {
            var dinheiro = Dinheiro.exato(valor.toString());
            if (dinheiro.positivo()) {
                return dinheiro;
            }
        } catch (IllegalArgumentException e) {
            // segue para o erro de campo abaixo
        }
        erros.put(campo, "deve ser um valor positivo com até 2 casas decimais");
        return null;
    }

    private static ContaId conta(Object valor, Map<String, Object> erros) {
        if (valor == null) {
            return null;
        }
        try {
            return new ContaId(UUID.fromString(valor.toString()));
        } catch (IllegalArgumentException e) {
            erros.put("conta", "deve ser um ID válido");
            return null;
        }
    }

    private static LocalDate data(Object valor, Map<String, Object> erros) {
        if (valor == null) {
            return null;
        }
        try {
            return LocalDate.parse(valor.toString());
        } catch (DateTimeParseException e) {
            erros.put("recebidoEm", "deve estar no formato AAAA-MM-DD");
            return null;
        }
    }
}
