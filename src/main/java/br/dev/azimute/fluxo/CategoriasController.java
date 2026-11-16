package br.dev.azimute.fluxo;

import br.dev.azimute.comum.CamposEditaveis;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Categorias de gasto (RF02–RF04). Exige token, como tudo sob {@code /api} (SEC01). */
@RestController
@RequestMapping("/api/categorias")
class CategoriasController {

    private static final CamposEditaveis CAMPOS_EDITAVEIS = new CamposEditaveis("nome", "ativa");

    record CriarCategoriaRequest(@NotBlank String nome) {}

    record CategoriaResponse(UUID id, String nome, boolean predefinida, boolean ativa) {
        static CategoriaResponse de(Categoria c) {
            return new CategoriaResponse(c.id().valor(), c.nome(), c.predefinida(), c.ativa());
        }
    }

    private final CategoriaService categorias;

    CategoriasController(CategoriaService categorias) {
        this.categorias = categorias;
    }

    /** Também serve à criação no meio do registro de gasto (RF02). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CategoriaResponse criar(@Valid @RequestBody CriarCategoriaRequest corpo) {
        return CategoriaResponse.de(categorias.criar(corpo.nome()));
    }

    @GetMapping
    List<CategoriaResponse> listar(@RequestParam(defaultValue = "false") boolean incluirInativas) {
        return categorias.listar(incluirInativas).stream().map(CategoriaResponse::de).toList();
    }

    /** Corpo como mapa, para nomear o campo proibido e distinguir ausente de inválido (RF04). */
    @PatchMapping("/{id}")
    CategoriaResponse alterar(@PathVariable UUID id, @RequestBody Map<String, Object> corpo) {
        CAMPOS_EDITAVEIS.conferir(corpo, p -> new CampoNaoEditavelException(p, "nome e ativa"));
        var erros = new LinkedHashMap<String, Object>();
        var nome = corpo.get("nome");
        if (nome != null && !(nome instanceof String texto && !texto.isBlank())) {
            erros.put("nome", "deve ser texto não vazio");
        }
        var ativa = corpo.get("ativa");
        if (ativa != null && !(ativa instanceof Boolean)) {
            erros.put("ativa", "deve ser true ou false");
        }
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException(erros);
        }
        return CategoriaResponse.de(categorias.alterar(new CategoriaId(id), (String) nome, (Boolean) ativa));
    }
}
