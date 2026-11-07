package br.dev.azimute.calculadora.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF10. */
class TaxaMensalTest {

    @Test
    void percentual_vira_fracao() {
        assertThat(TaxaMensal.dePercentual("3.51").fracao()).isEqualByComparingTo("0.0351");
        assertThat(TaxaMensal.dePercentual("3.51").percentual()).isEqualByComparingTo("3.51");
    }

    @Test
    void taxa_negativa_e_rejeitada() {
        assertThatThrownBy(() -> new TaxaMensal(new BigDecimal("-0.01")))
                .isInstanceOf(ValorInvalidoException.class);
        assertThatThrownBy(() -> TaxaMensal.dePercentual("-1")).isInstanceOf(ValorInvalidoException.class);
    }

    @Test
    void nula_e_rejeitada() {
        assertThatThrownBy(() -> new TaxaMensal(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void zero_e_valida() {
        assertThat(TaxaMensal.ZERO.fracao()).isEqualByComparingTo("0");
        assertThat(TaxaMensal.ZERO.anualizada().fracao()).isEqualByComparingTo("0");
    }

    @Test
    void anualizada_compoe_doze_meses() {
        // (1,01)^12 − 1 = 0,126825030131…
        var anual = new TaxaMensal(new BigDecimal("0.01")).anualizada().fracao();
        assertThat(anual.setScale(10, RoundingMode.HALF_EVEN)).isEqualByComparingTo("0.1268250301");
    }

    @Test
    void anualizada_das_propostas_reais() {
        assertThat(TaxaMensal.dePercentual("3.51349").anualizada().percentual().setScale(1, RoundingMode.HALF_UP))
                .isEqualByComparingTo("51.3");
        assertThat(TaxaMensal.dePercentual("4.13642").anualizada().percentual().setScale(1, RoundingMode.HALF_UP))
                .isEqualByComparingTo("62.6");
    }
}
