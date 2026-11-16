package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.folha.HoleriteId;
import br.dev.azimute.fluxo.tabelas.PapelDeRecebimento;
import br.dev.azimute.fluxo.tabelas.RecebimentoId;
import br.dev.azimute.fluxo.tabelas.TipoGanho;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF21, RN02, RN03: o modelo recusa o que o banco também recusaria, antes de chegar nele. */
class ModeloDoFluxoTest {

    private static final Competencia NOVEMBRO = Competencia.de("2026-11");
    private static final LocalDate DIA = LocalDate.of(2026, 11, 14);
    private static final ContaId CONTA = new ContaId(UUID.randomUUID());
    private static final HoleriteId HOLERITE = new HoleriteId(UUID.randomUUID());

    @Test
    void tipos_de_ganho_sao_os_cinco_da_lista_fixa() {
        assertThat(TipoGanho.values()).extracting(Enum::name)
                .containsExactly("SALARIO", "PLR", "FREELA", "REEMBOLSO", "OUTRO");
    }

    @Test
    void ganho_com_liquido_maior_que_bruto_e_erro_de_dominio() {
        assertThatThrownBy(() -> ganho(TipoGanho.FREELA, "100", "100.01", null))
                .isInstanceOfSatisfying(LiquidoMaiorQueBrutoException.class, e -> {
                    assertThat(e.codigo()).isEqualTo("LIQUIDO_MAIOR_QUE_BRUTO");
                    assertThat(e.detalhes()).containsEntry("bruto", "100.00").containsEntry("liquido", "100.01");
                });
        assertThatCode(() -> ganho(TipoGanho.FREELA, "100", "100", null)).doesNotThrowAnyException();
    }

    @Test
    void ganho_com_valor_negativo_e_recusado() {
        assertThatThrownBy(() -> ganho(TipoGanho.OUTRO, "-1", "-2", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ganho(TipoGanho.OUTRO, "10", "-1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void salario_so_com_holerite_e_os_outros_sem() {
        assertThatThrownBy(() -> ganho(TipoGanho.SALARIO, "5000", "3800", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ganho(TipoGanho.PLR, "5000", "3800", HOLERITE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ganho(TipoGanho.SALARIO, "5000", "3800", HOLERITE).holerite()).isEqualTo(HOLERITE);
    }

    @Test
    void ganho_exige_descricao() {
        assertThatThrownBy(() -> new Ganho(new GanhoId(UUID.randomUUID()), NOVEMBRO, "  ", TipoGanho.OUTRO,
                Dinheiro.de("1"), Dinheiro.de("1"), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gasto_precisa_de_valor_positivo_e_descricao() {
        assertThatThrownBy(() -> gasto("0", "Mercado")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gasto("-1", "Mercado")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gasto("10", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(gasto("10", "Mercado").forma()).isEqualTo(new FormaPagamento.AVista(CONTA));
    }

    @Test
    void recebimento_precisa_de_valor_positivo() {
        assertThatThrownBy(() -> recebimento("0")).isInstanceOf(IllegalArgumentException.class);
        assertThat(recebimento("2500").papel()).isEqualTo(PapelDeRecebimento.ADIANTAMENTO);
        assertThat(PapelDeRecebimento.values()).extracting(Enum::name)
                .containsExactly("AVULSO", "ADIANTAMENTO", "LIQUIDO");
    }

    @Test
    void categoria_exige_nome() {
        assertThatThrownBy(() -> new Categoria(new CategoriaId(UUID.randomUUID()), " ", false, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Categoria(new CategoriaId(UUID.randomUUID()), "Lazer", true, true).predefinida()).isTrue();
    }

    @Test
    void ids_nao_aceitam_nulo_e_mostram_o_uuid() {
        var uuid = UUID.randomUUID();

        assertThat(new GastoId(uuid)).hasToString(uuid.toString());
        assertThat(new GanhoId(uuid)).hasToString(uuid.toString());
        assertThat(new CategoriaId(uuid)).hasToString(uuid.toString());
        assertThat(new HoleriteId(uuid)).hasToString(uuid.toString());
        assertThatThrownBy(() -> new GastoId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GanhoId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CategoriaId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HoleriteId(null)).isInstanceOf(NullPointerException.class);
    }

    private static Ganho ganho(TipoGanho tipo, String bruto, String liquido, HoleriteId holerite) {
        return new Ganho(new GanhoId(UUID.randomUUID()), NOVEMBRO, "Ganho", tipo, Dinheiro.de(bruto),
                Dinheiro.de(liquido), holerite);
    }

    private static Gasto gasto(String valor, String descricao) {
        return new Gasto(new GastoId(UUID.randomUUID()), DIA, descricao, Dinheiro.de(valor),
                new CategoriaId(UUID.randomUUID()), new FormaPagamento.AVista(CONTA));
    }

    private static Recebimento recebimento(String valor) {
        return new Recebimento(new RecebimentoId(UUID.randomUUID()), null, NOVEMBRO, PapelDeRecebimento.ADIANTAMENTO, Dinheiro.de(valor),
                DIA, CONTA, true);
    }
}
