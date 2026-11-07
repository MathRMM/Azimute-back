package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Resposta do {@code GET /comparar} (contrato na seção 6.5 do SPEC). */
record ComparacaoResponse(String bem, List<Item> propostas, Dinheiro economiaEntreMelhorEPior) {

    record Item(UUID id, String instituicao, String local, LocalDate data, OrigemSimulacao origem,
                StatusSimulacao status, String taxaMensal, int prazo, Dinheiro valorFinanciado,
                Dinheiro parcela, Dinheiro totalJuros, boolean melhor) {
    }

    static ComparacaoResponse de(Comparacao c) {
        var melhor = c.melhor();
        var itens = c.propostas().stream().map(s -> {
            var d = s.dados();
            return new Item(s.id().valor(), d.instituicao(), d.local(), d.data(), d.origem(), s.status(),
                    d.taxaMensal().toPlainString(), d.prazo(), d.valorFinanciado(), d.parcela(), d.totalJuros(),
                    s == melhor);
        }).toList();
        return new ComparacaoResponse(c.bem(), itens, c.economiaEntreMelhorEPior());
    }
}
