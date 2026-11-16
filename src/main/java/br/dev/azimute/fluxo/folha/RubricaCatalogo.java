package br.dev.azimute.fluxo.folha;

import br.dev.azimute.fluxo.CategoriaId;

import java.util.Objects;

/**
 * Como o sistema interpreta um código de rubrica (RF35). Rubrica nova entra marcada como a classificar, com
 * natureza provisória pela coluna em que apareceu (RF36, T11).
 */
record RubricaCatalogo(String codigo, String descricao, NaturezaRubrica natureza, CategoriaId categoria,
                       boolean aClassificar) {

    RubricaCatalogo {
        Objects.requireNonNull(codigo, "codigo");
        Objects.requireNonNull(descricao, "descricao");
        Objects.requireNonNull(natureza, "natureza");
        if ((natureza == NaturezaRubrica.GASTO) != (categoria != null)) {
            throw new IllegalArgumentException("Só a rubrica de gasto tem categoria, e sempre tem: " + codigo);
        }
    }
}
