package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/**
 * O PDF não pôde ser lido no layout conhecido. Aponta o campo que falhou, nunca o conteúdo (SEC02): o usuário lança
 * o mesmo holerite pelo caminho manual (RF32).
 */
final class HoleriteIlegivelException extends ErroDeNegocio {

    HoleriteIlegivelException(String campo) {
        super("HOLERITE_ILEGIVEL", "Não foi possível ler o holerite; lance-o pelo caminho manual", Tipo.VALIDACAO,
                Map.of("campo", campo));
    }
}
