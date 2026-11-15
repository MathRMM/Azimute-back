package br.dev.azimute.fluxo;

import br.dev.azimute.TestcontainersConfig;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF52, T13: {@code GastoLancado} só chega a quem ouve depois do commit; transação desfeita não publica. */
@SpringBootTest
@Import({TestcontainersConfig.class, GastoLancadoIT.Ouvinte.class})
class GastoLancadoIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class Ouvinte {

        @Bean
        Registro registro() {
            return new Registro(new CopyOnWriteArrayList<>());
        }

        /** Classe aberta, não record: o Spring cria um proxy do ouvinte. */
        static class Registro {
            private final List<GastoLancado> recebidos;

            Registro(List<GastoLancado> recebidos) {
                this.recebidos = recebidos;
            }

            List<GastoLancado> recebidos() {
                return recebidos;
            }

            @TransactionalEventListener
            void aoLancar(GastoLancado evento) {
                recebidos.add(evento);
            }
        }
    }

    @Autowired GastoService gastos;
    @Autowired Ouvinte.Registro ouvinte;
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
