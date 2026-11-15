package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RN10: antes de lançar qualquer coisa, os totais do holerite precisam bater com as rubricas. */
class ConferenciaDeHoleriteTest {

    @Test
    void holerite_do_exemplo_passa() {
        assertThatCode(() -> ConferenciaDeHolerite.conferir(HoleriteDeExemplo.lido())).doesNotThrowAnyException();
    }

    @Test
    void vencimentos_somando_diferente_do_total_aponta_vencimentos() {
        var rubricas = new ArrayList<>(HoleriteDeExemplo.rubricas());
        rubricas.add(HoleriteDeExemplo.vencimento("0002", "Hora extra", "10"));
        var lido = new HoleriteLido(HoleriteDeExemplo.OUTUBRO, rubricas, Dinheiro.de("5000"), Dinheiro.de("3580"),
                Dinheiro.de("1420"), HoleriteDeExemplo.bases());

        assertThatThrownBy(() -> ConferenciaDeHolerite.conferir(lido))
                .isInstanceOfSatisfying(HoleriteInconsistenteException.class, e -> {
                    assertThat(e.codigo()).isEqualTo("HOLERITE_INCONSISTENTE");
                    assertThat(e.detalhes()).containsEntry("conferencia", "vencimentos")
                            .containsEntry("informado", "5000.00").containsEntry("calculado", "5010.00");
                });
    }

    @Test
    void descontos_somando_diferente_do_total_aponta_descontos() {
        var lido = new HoleriteLido(HoleriteDeExemplo.OUTUBRO, HoleriteDeExemplo.rubricas(), Dinheiro.de("5000"),
                Dinheiro.de("3500"), Dinheiro.de("1500"), HoleriteDeExemplo.bases());

        assertThatThrownBy(() -> ConferenciaDeHolerite.conferir(lido))
                .isInstanceOfSatisfying(HoleriteInconsistenteException.class,
                        e -> assertThat(e.detalhes()).containsEntry("conferencia", "descontos"));
    }

    @Test
    void liquido_errado_aponta_liquido() {
        var lido = new HoleriteLido(HoleriteDeExemplo.OUTUBRO, HoleriteDeExemplo.rubricas(), Dinheiro.de("5000"),
                Dinheiro.de("3580"), Dinheiro.de("1400"), HoleriteDeExemplo.bases());

        assertThatThrownBy(() -> ConferenciaDeHolerite.conferir(lido))
                .isInstanceOfSatisfying(HoleriteInconsistenteException.class, e -> assertThat(e.detalhes())
                        .containsEntry("conferencia", "liquido").containsEntry("calculado", "1420.00"));
    }
}
