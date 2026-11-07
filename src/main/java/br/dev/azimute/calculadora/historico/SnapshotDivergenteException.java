package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.ErroDeNegocio;

import java.util.Map;

/** Os resultados enviados pelo cliente não batem com o recálculo do core (RF28, decisão D6). */
final class SnapshotDivergenteException extends ErroDeNegocio {

    SnapshotDivergenteException(Map<String, Object> campos) {
        super("SNAPSHOT_DIVERGENTE",
                "Os valores calculados enviados não conferem com os dados da simulação",
                Tipo.VALIDACAO, Map.of("campos", campos));
    }
}
