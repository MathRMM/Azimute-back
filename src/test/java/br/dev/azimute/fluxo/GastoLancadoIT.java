package br.dev.azimute.fluxo;

import br.dev.azimute.TestcontainersConfig;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF52, T13: {@code GastoLancado} só chega a quem ouve depois do commit; transação desfeita não publica. */
@SpringBootTest
@Import({TestcontainersConfig.class, OuvinteDeEventos.class})
class GastoLancadoIT {

    @Autowired GastoService gastos;
    @Autowired OuvinteDeEventos.Registro ouvinte;
    @Autowired JdbcClient jdbc;

    MontadorDeFluxo fluxo;

    @BeforeEach
    void preparar() {
        ouvinte.recebidos().clear();
        fluxo = new MontadorDeFluxo(jdbc);
    }

    @Test
    void registrar_publica_depois_do_commit_e_o_reenvio_nao_publica_de_novo() {
        var conta = new ContaId(fluxo.conta());
        var categoria = new CategoriaId(fluxo.categoria());
        var id = new GastoId(UUID.randomUUID());
        var novo = new NovoGasto(LocalDate.of(2026, 11, 14), "Mercado", Dinheiro.de("80"), categoria, conta);

        gastos.registrar(id, novo);
        gastos.registrar(id, novo);

        assertThat(ouvinte.recebidos())
                .containsExactly(new GastoLancado(id, LocalDate.of(2026, 11, 14), Dinheiro.de("80"), categoria));
    }

    @Test
    void transacao_desfeita_nao_publica_e_nao_grava() {
        var conta = new ContaId(fluxo.conta());
        jdbc.sql("UPDATE contas.contas SET ativa = false WHERE id = :id").param("id", conta.valor()).update();
        var id = new GastoId(UUID.randomUUID());

        assertThatThrownBy(() -> gastos.registrar(id, new NovoGasto(LocalDate.of(2026, 11, 14), "Mercado",
                Dinheiro.de("80"), new CategoriaId(fluxo.categoria()), conta)))
                .hasMessageContaining("desativada");

        assertThat(ouvinte.recebidos()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM fluxo.gastos WHERE id = :id").param("id", id.valor())
                .query(Long.class).single()).isZero();
    }
}
