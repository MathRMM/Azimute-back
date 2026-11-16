package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.comum.Taxa;
import br.dev.azimute.fluxo.CategoriaId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF35, RF35a, RN02, T7, T11: o contrato do holerite lido e o catálogo de rubricas. */
class ModeloDaFolhaTest {

    private static final Competencia NOVEMBRO = Competencia.de("2026-11");
    private static final BasesLidas BASES = new BasesLidas(Dinheiro.de("5000"), Dinheiro.de("5000"),
            Dinheiro.de("5000"), Dinheiro.de("400"), Dinheiro.de("4450"));

    @Test
    void naturezas_sao_as_oito_do_catalogo() {
        assertThat(NaturezaRubrica.values()).extracting(Enum::name).containsExactly("PROVENTO", "INSS", "IRRF",
                "PREVIDENCIA_BASICA", "PREVIDENCIA_VOLUNTARIA", "ADIANTAMENTO", "GASTO", "OUTRO_DESCONTO");
    }

    @Test
    void rubrica_lida_tem_vencimento_ou_desconto_nunca_os_dois() {
        assertThatCode(() -> new RubricaLida("0001", "Salário", null, Dinheiro.de("5000"), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new RubricaLida("/314", "INSS", new BigDecimal("14"), null, Dinheiro.de("500")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new RubricaLida("0001", "Salário", null, Dinheiro.de("1"), Dinheiro.de("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RubricaLida("0001", "Salário", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RubricaLida(" ", "Salário", null, Dinheiro.de("1"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void holerite_lido_guarda_uma_copia_das_rubricas() {
        var rubricas = new ArrayList<>(List.of(new RubricaLida("0001", "Salário", null, Dinheiro.de("5000"), null)));
        var lido = new HoleriteLido(NOVEMBRO, rubricas, Dinheiro.de("5000"), Dinheiro.ZERO, Dinheiro.de("5000"),
                BASES);

        rubricas.clear();

        assertThat(lido.rubricas()).hasSize(1);
        assertThatThrownBy(() -> new HoleriteLido(null, List.of(), Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO,
                BASES)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rubrica_de_gasto_exige_categoria_e_as_outras_nao_tem() {
        var transporte = new CategoriaId(UUID.randomUUID());

        assertThat(new RubricaCatalogo("/507", "Vale-Transporte", NaturezaRubrica.GASTO, transporte, false)
                .categoria()).isEqualTo(transporte);
        assertThatThrownBy(() -> new RubricaCatalogo("/507", "Vale-Transporte", NaturezaRubrica.GASTO, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RubricaCatalogo("/314", "INSS", NaturezaRubrica.INSS, transporte, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void regra_de_contrapartida_exige_competencia_e_taxa() {
        assertThat(new RegraContrapartida(Competencia.de("2000-01"), Taxa.dePontos("100")).percentualDaBasica()
                .fracao()).isEqualByComparingTo("1");
        assertThatCode(() -> new RegraContrapartida(NOVEMBRO, Taxa.ZERO)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new RegraContrapartida(NOVEMBRO, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RegraContrapartida(null, Taxa.ZERO)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void regra_vigente_e_a_de_maior_inicio_ate_a_competencia_e_a_contrapartida_arredonda() {
        var regras = List.of(new RegraContrapartida(Competencia.de("2000-01"), Taxa.dePontos("100")),
                new RegraContrapartida(Competencia.de("2027-01"), Taxa.dePontos("50")));

        assertThat(RegraContrapartida.vigente(regras, Competencia.de("2026-12")).percentualDaBasica())
                .isEqualTo(Taxa.dePontos("100"));
        assertThat(RegraContrapartida.vigente(regras, Competencia.de("2027-01")).percentualDaBasica())
                .isEqualTo(Taxa.dePontos("50"));
        assertThat(RegraContrapartida.vigente(List.of(), NOVEMBRO).percentualDaBasica()).isEqualTo(Taxa.ZERO);
        assertThat(new RegraContrapartida(NOVEMBRO, Taxa.dePontos("50")).contrapartida(Dinheiro.de("50.05")))
                .isEqualTo(Dinheiro.de("25.02"));
    }

    @Test
    void holerite_recusa_totais_incoerentes() {
        assertThatCode(() -> holerite("3800")).doesNotThrowAnyException();
        assertThatThrownBy(() -> holerite("3700")).isInstanceOf(IllegalArgumentException.class);
        assertThat(holerite("3800").origem()).isEqualTo(Holerite.Origem.PDF);
        assertThat(Holerite.Origem.values()).extracting(Enum::name).containsExactly("PDF", "MANUAL");
    }

    private static Holerite holerite(String liquido) {
        return new Holerite(new HoleriteId(UUID.randomUUID()), NOVEMBRO, Holerite.Origem.PDF, "hash",
                Dinheiro.de("5000"), Dinheiro.de("1200"), Dinheiro.de(liquido), BASES, Dinheiro.de("150"),
                LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 30));
    }
}
