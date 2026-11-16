package br.dev.azimute.fluxo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Categorias de gasto (RF02–RF04): não se excluem, se desativam (RN05); nome único (RN06). */
@Service
class CategoriaService {

    private final CategoriaRepository categorias;

    CategoriaService(CategoriaRepository categorias) {
        this.categorias = categorias;
    }

    Categoria criar(String nome) {
        return categorias.inserir(nome.strip());
    }

    List<Categoria> listar(boolean incluirInativas) {
        return categorias.listar(incluirInativas);
    }

    Optional<Categoria> buscar(CategoriaId id) {
        return categorias.buscar(id);
    }

    /** Nulo quer dizer "não muda". */
    @Transactional
    Categoria alterar(CategoriaId id, String nome, Boolean ativa) {
        var atual = categorias.buscar(id).orElseThrow(() -> new CategoriaNaoEncontradaException(id));
        var alterada = new Categoria(id, nome == null ? atual.nome() : nome.strip(), atual.predefinida(),
                ativa == null ? atual.ativa() : ativa);
        categorias.atualizar(alterada);
        return alterada;
    }

    /** Para gasto novo ou editado: inexistente → 404, inativa → 422 (RN05). */
    Categoria exigirAtiva(CategoriaId id) {
        var categoria = categorias.buscar(id).orElseThrow(() -> new CategoriaNaoEncontradaException(id));
        if (!categoria.ativa()) {
            throw new CategoriaInativaException(id);
        }
        return categoria;
    }
}
