package br.dev.azimute.comum;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Os campos que um PATCH pode alterar. O corpo é lido como mapa para nomear qualquer campo proibido; quem lança o
 * erro é o módulo, com a sua exceção.
 */
public record CamposEditaveis(Set<String> nomes) {

    public CamposEditaveis {
        nomes = Set.copyOf(nomes);
    }

    public CamposEditaveis(String... nomes) {
        this(Set.of(nomes));
    }

    /** Lança o erro do módulo com os campos proibidos em ordem alfabética, se houver algum. */
    public void conferir(Map<String, ?> corpo, Function<List<String>, ? extends ErroDeCampoNaoEditavel> erro) {
        var proibidos = corpo.keySet().stream().filter(c -> !nomes.contains(c)).sorted().toList();
        if (!proibidos.isEmpty()) {
            throw erro.apply(proibidos);
        }
    }
}
