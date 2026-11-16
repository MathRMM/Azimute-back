package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RF50, RN17, T14: o resumo do mês sem banco. */
class ResumoServiceTest {

    private static final Competencia OUTUBRO = Competencia.de("2026-10");
    private static final CategoriaId LAZER = new CategoriaId(UUID.randomUUID());
    private static final CategoriaId MERCADO = new CategoriaId(UUID.randomUUID());
    private static final CategoriaId TRANSPORTE = new CategoriaId(UUID.randomUUID());

    private final ResumoRepository repositorio = mock(ResumoRepository.class);
    private final ResumoService servico = new ResumoService(repositorio);

    @Test
    void media_dos_tres_meses_anteriores_conta_o_mes_sem_gasto_como_zero() {
        // Lazer: 90 em julho, 60 em agosto, nada em setembro → média 50.
        when(repositorio.gastos(OUTUBRO)).thenReturn(List.of(
                gasto(LAZER, "Lazer", "30"), gasto(MERCADO, "Mercado", "200"), gasto(MERCADO, "Mercado", "100.50")));
        when(repositorio.totaisDosTresMesesAnteriores(OUTUBRO)).thenReturn(List.of(
                total(LAZER, "Lazer", "150"), total(MERCADO, "Mercado", "100")));
        when(repositorio.recebido(OUTUBRO)).thenReturn(Dinheiro.de("1000"));

        var resumo = servico.resumoDoMes(OUTUBRO);

        assertThat(resumo.competencia()).isEqualTo(OUTUBRO);
        assertThat(resumo.totalGasto()).isEqualTo(Dinheiro.de("330.50"));
        assertThat(resumo.recebido()).isEqualTo(Dinheiro.de("1000"));
        assertThat(resumo.sobra()).isEqualTo(Dinheiro.de("669.50"));
        assertThat(resumo.categorias()).containsExactly(
                new ResumoMes.PorCategoria(MERCADO, "Mercado", Dinheiro.de("300.50"), Dinheiro.de("33.33")),
                new ResumoMes.PorCategoria(LAZER, "Lazer", Dinheiro.de("30"), Dinheiro.de("50")));
    }

    @Test
    void categoria_so_com_historico_aparece_com_total_zero() {
        when(repositorio.gastos(OUTUBRO)).thenReturn(List.of());
        when(repositorio.totaisDosTresMesesAnteriores(OUTUBRO)).thenReturn(List.of(
                total(TRANSPORTE, "Transporte", "900")));
        when(repositorio.recebido(OUTUBRO)).thenReturn(Dinheiro.ZERO);

        var resumo = servico.resumoDoMes(OUTUBRO);

        assertThat(resumo.categorias()).containsExactly(
                new ResumoMes.PorCategoria(TRANSPORTE, "Transporte", Dinheiro.ZERO, Dinheiro.de("300")));
        assertThat(resumo.sobra()).isEqualTo(Dinheiro.ZERO);
    }

    @Test
    void competencia_vazia_e_tudo_zero() {
        when(repositorio.gastos(OUTUBRO)).thenReturn(List.of());
        when(repositorio.totaisDosTresMesesAnteriores(OUTUBRO)).thenReturn(List.of());
        when(repositorio.recebido(OUTUBRO)).thenReturn(Dinheiro.ZERO);

        var resumo = servico.resumoDoMes(OUTUBRO);

        assertThat(resumo).isEqualTo(new ResumoMes(OUTUBRO, Dinheiro.ZERO, Dinheiro.ZERO, Dinheiro.ZERO, List.of()));
    }

    @Test
    void gasto_maior_que_o_recebido_deixa_sobra_negativa() {
        when(repositorio.gastos(OUTUBRO)).thenReturn(List.of(gasto(LAZER, "Lazer", "500")));
        when(repositorio.totaisDosTresMesesAnteriores(OUTUBRO)).thenReturn(List.of());
        when(repositorio.recebido(OUTUBRO)).thenReturn(Dinheiro.de("100"));

        assertThat(servico.resumoDoMes(OUTUBRO).sobra()).isEqualTo(Dinheiro.de("-400"));
    }

    @Property
    void total_gasto_e_a_soma_dos_totais_por_categoria(
            @ForAll @Size(max = 40) List<@IntRange(min = 0, max = 4) Integer> categorias,
            @ForAll @Size(max = 40) List<@IntRange(min = 1, max = 1_000_000) Integer> centavos) {
        var ids = List.of(LAZER, MERCADO, TRANSPORTE, new CategoriaId(UUID.randomUUID()),
                new CategoriaId(UUID.randomUUID()));
        var gastos = new ArrayList<ResumoRepository.GastoDoMes>();
        for (int i = 0; i < Math.min(categorias.size(), centavos.size()); i++) {
            var id = ids.get(categorias.get(i));
            gastos.add(new ResumoRepository.GastoDoMes(id, "C" + categorias.get(i),
                    new Dinheiro(java.math.BigDecimal.valueOf(centavos.get(i), 2))));
        }

        var resumo = ResumoService.montar(OUTUBRO, gastos, List.of(), Dinheiro.ZERO);

        var somaDasCategorias = resumo.categorias().stream().map(ResumoMes.PorCategoria::total)
                .reduce(Dinheiro.ZERO, Dinheiro::mais);
        assertThat(resumo.totalGasto()).isEqualTo(somaDasCategorias);
        assertThat(resumo.sobra()).isEqualTo(Dinheiro.ZERO.menos(somaDasCategorias));
    }

    private static ResumoRepository.GastoDoMes gasto(CategoriaId id, String nome, String valor) {
        return new ResumoRepository.GastoDoMes(id, nome, Dinheiro.de(valor));
    }

    private static ResumoRepository.GastoDoMes total(CategoriaId id, String nome, String valor) {
        return gasto(id, nome, valor);
    }
}
