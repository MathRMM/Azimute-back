package br.dev.azimute.fluxo.folha;

import br.dev.azimute.fluxo.CategoriaId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Catálogo de rubricas (RF35, RF36). Reclassificar refaz os efeitos dos holerites que usam a rubrica (RN13); a
 * contrapartida gravada de cada holerite não muda (RN19).
 */
@Service
class CatalogoService {

    private final CatalogoRepository catalogo;
    private final HoleriteService holerites;

    CatalogoService(CatalogoRepository catalogo, HoleriteService holerites) {
        this.catalogo = catalogo;
        this.holerites = holerites;
    }

    List<RubricaCatalogo> listar() {
        return catalogo.listar();
    }

    /** A combinação de natureza e categoria já vem conferida; aqui, a rubrica e a categoria precisam existir. */
    @Transactional
    RubricaCatalogo reclassificar(String codigo, NaturezaRubrica natureza, CategoriaId categoria) {
        var atual = catalogo.buscar(codigo).orElseThrow(() -> new RubricaNaoEncontradaException(codigo));
        if (categoria != null) {
            var ativa = catalogo.categoriaAtiva(categoria)
                    .orElseThrow(() -> CategoriaDaRubricaException.naoEncontrada(categoria));
            if (!ativa) {
                throw CategoriaDaRubricaException.inativa(categoria);
            }
        }
        catalogo.reclassificar(codigo, natureza, categoria);
        holerites.reaplicarComRubrica(codigo);
        return new RubricaCatalogo(codigo, atual.descricao(), natureza, categoria, false);
    }
}
