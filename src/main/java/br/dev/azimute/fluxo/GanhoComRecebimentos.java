package br.dev.azimute.fluxo;

import java.util.List;

/** O ganho e o que dele caiu nas contas (RF23, RF24). */
record GanhoComRecebimentos(Ganho ganho, List<Recebimento> recebimentos) {

    GanhoComRecebimentos {
        recebimentos = List.copyOf(recebimentos);
    }
}
