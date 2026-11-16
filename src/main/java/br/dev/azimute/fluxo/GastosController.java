package br.dev.azimute.fluxo;

import br.dev.azimute.comum.CamposEditaveis;
import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.tabelas.FormaDeGasto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Gastos (RF10–RF13, RF15). Exige token, como tudo sob {@code /api} (SEC01). */
@RestController
@RequestMapping("/api/gastos")
class GastosController {

    private static final CamposEditaveis CAMPOS_EDITAVEIS = new CamposEditaveis("data", "descricao", "valor", "categoria", "conta");

    /** Valor positivo com até 2 casas, sem arredondar (RN03). Data futura é aceita (RN08). */
    record RegistrarGastoRequest(
            @NotNull LocalDate data,
            @NotBlank String descricao,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal valor,
            @NotNull UUID categoria,
            @NotNull UUID conta) {

        NovoGasto paraDominio() {
            return new NovoGasto(data, descricao, new Dinheiro(valor), new CategoriaId(categoria), new ContaId(conta));
        }
    }

    /** Com o nome da categoria e a forma de pagamento (RF13). {@code conta} só no à vista; {@code holerite}, em folha. */
    record GastoResponse(UUID id, LocalDate data, String descricao, Dinheiro valor, UUID categoria,
                         String categoriaNome, FormaDeGasto forma, UUID conta, UUID holerite) {

        static GastoResponse de(GastoComCategoria item) {
            var g = item.gasto();
            UUID conta = null;
            UUID holerite = null;
            switch (g.forma()) {
                case FormaPagamento.AVista a -> conta = a.conta().valor();
                case FormaPagamento.DescontoEmFolha d -> holerite = d.holerite().valor();
            }
            return new GastoResponse(g.id().valor(), g.data(), g.descricao(), g.valor(), g.categoria().valor(),
                    item.categoriaNome(), g.forma().codigo(), conta, holerite);
        }
    }

    private final GastoService gastos;

    GastosController(GastoService gastos) {
        this.gastos = gastos;
    }

    /** PUT com ID do cliente: o reenvio pela fila offline não duplica (RF10). */
    @PutMapping("/{id}")
    ResponseEntity<GastoResponse> registrar(@PathVariable UUID id, @Valid @RequestBody RegistrarGastoRequest corpo) {
        var resultado = gastos.registrar(new GastoId(id), corpo.paraDominio());
        return ResponseEntity.status(resultado.criado() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(GastoResponse.de(resultado.gasto()));
    }

    /** Corpo como mapa, para nomear o campo proibido e distinguir ausente de inválido (RF11). */
    @PatchMapping("/{id}")
    GastoResponse alterar(@PathVariable UUID id, @RequestBody Map<String, Object> corpo) {
        CAMPOS_EDITAVEIS.conferir(corpo, p -> new CampoNaoEditavelException(p, "data, descrição, valor, categoria e conta"));
        return GastoResponse.de(gastos.alterar(new GastoId(id), lerAlteracao(corpo)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void excluir(@PathVariable UUID id) {
        gastos.excluir(new GastoId(id));
    }

    @PostMapping("/{id}/restaurar")
    GastoResponse restaurar(@PathVariable UUID id) {
        return GastoResponse.de(gastos.restaurar(new GastoId(id)));
    }

    @GetMapping
    List<GastoResponse> listar(@RequestParam String competencia, @RequestParam(required = false) UUID categoria) {
        return gastos.listar(competencia(competencia), categoria == null ? null : new CategoriaId(categoria))
                .stream().map(GastoResponse::de).toList();
    }

    private static Competencia competencia(String texto) {
        try {
            return Competencia.de(texto);
        } catch (IllegalArgumentException e) {
            throw new CompetenciaInvalidaException(texto);
        }
    }

    private static AlteracaoGasto lerAlteracao(Map<String, Object> corpo) {
        var erros = new LinkedHashMap<String, Object>();
        var data = data(corpo.get("data"), erros);
        var descricao = descricao(corpo.get("descricao"), erros);
        var valor = valor(corpo.get("valor"), erros);
        var categoria = uuid(corpo, "categoria", erros);
        var conta = uuid(corpo, "conta", erros);
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException(erros);
        }
        return new AlteracaoGasto(data, descricao, valor, categoria == null ? null : new CategoriaId(categoria),
                conta == null ? null : new ContaId(conta));
    }

    private static LocalDate data(Object valor, Map<String, Object> erros) {
        if (valor == null) {
            return null;
        }
        try {
            return LocalDate.parse(valor.toString());
        } catch (DateTimeParseException e) {
            erros.put("data", "deve estar no formato AAAA-MM-DD");
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

    /** Mesmas regras do PUT: positivo e no máximo 2 casas, sem arredondar (RN03). */
    private static Dinheiro valor(Object valor, Map<String, Object> erros) {
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
        erros.put("valor", "deve ser um valor positivo com até 2 casas decimais");
        return null;
    }

    private static UUID uuid(Map<String, Object> corpo, String campo, Map<String, Object> erros) {
        var valor = corpo.get(campo);
        if (valor == null) {
            return null;
        }
        if (valor instanceof String texto) {
            try {
                return UUID.fromString(texto);
            } catch (IllegalArgumentException e) {
                // segue para o erro de campo abaixo
            }
        }
        erros.put(campo, "deve ser um ID válido");
        return null;
    }
}
