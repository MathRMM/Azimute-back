package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;
import java.util.UUID;

/** Mesmo ID com outro conteúdo no {@code PUT} do adiantamento (RF34). */
final class AdiantamentoDivergenteException extends ErroDeNegocio {

    AdiantamentoDivergenteException(UUID id) {
        super("ADIANTAMENTO_DIVERGENTE", "Já existe um recebimento com esse ID e outro conteúdo", Tipo.CONFLITO,
                Map.of("id", id.toString()));
    }
}
