package br.dev.azimute.fluxo;

import br.dev.azimute.TestcontainersConfig;
import br.dev.azimute.comum.Competencia;
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

/** RF52, T13: {@code GanhoRegistrado} só chega depois do commit; reenvio e transação desfeita não publicam. */
@SpringBootTest
@Import({TestcontainersConfig.class, OuvinteDeEventos.class})
class GanhoRegistradoIT {

    @Autowired GanhoService ganhos;
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
        var id = new GanhoId(UUID.randomUUID());
        var novo = novo(new ContaId(fluxo.conta()));

        ganhos.registrar(id, novo);
        ganhos.registrar(id, novo);

        assertThat(ouvinte.recebidos())
                .containsExactly(new GanhoRegistrado(id, Competencia.de("2026-10"), Dinheiro.de("450")));
    }

    @Test
    void transacao_desfeita_nao_publica_e_nao_grava() {
        var conta = new ContaId(fluxo.conta());
        jdbc.sql("UPDATE contas.contas SET ativa = false WHERE id = :id").param("id", conta.valor()).update();
        var id = new GanhoId(UUID.randomUUID());

        assertThatThrownBy(() -> ganhos.registrar(id, novo(conta))).hasMessageContaining("desativada");

        assertThat(ouvinte.recebidos()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM fluxo.ganhos WHERE id = :id").param("id", id.valor())
                .query(Long.class).single()).isZero();
    }

    private static NovoGanho novo(ContaId conta) {
        return new NovoGanho(Competencia.de("2026-10"), "Freela", TipoGanho.FREELA, Dinheiro.de("500"),
                Dinheiro.de("450"), conta, LocalDate.of(2026, 11, 3));
    }
}
