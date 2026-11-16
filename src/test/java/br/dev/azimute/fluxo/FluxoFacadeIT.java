package br.dev.azimute.fluxo;

import br.dev.azimute.ClienteDeTeste;
import br.dev.azimute.TestcontainersConfig;
import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** RF51: a fachada que {@code sistema} e {@code agenda} vão usar faz o mesmo que a API. */
@SpringBootTest
@Import(TestcontainersConfig.class)
class FluxoFacadeIT {

    @Autowired FluxoFacade fluxo;
    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @Test
    void registrar_gasto_grava_e_lanca_a_saida_como_o_put_e_o_reenvio_nao_duplica() {
        var montador = new MontadorDeFluxo(jdbc);
        var conta = new ContaId(montador.conta());
        var categoria = new CategoriaId(montador.categoria());
        var comando = new RegistrarGasto(new GastoId(UUID.randomUUID()), LocalDate.of(2099, 3, 10), "Padaria",
                Dinheiro.de("12.50"), categoria, conta);

        assertThat(fluxo.registrarGasto(comando)).isEqualTo(comando.id());
        assertThat(fluxo.registrarGasto(comando)).isEqualTo(comando.id());

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM contas.movimentacoes
                        WHERE gasto_id = :id AND tipo = 'SAIDA' AND valor = 12.50 AND deleted_at IS NULL""")
                .param("id", comando.id().valor()).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM fluxo.gastos WHERE id = :id").param("id", comando.id().valor())
                .query(Long.class).single()).isOne();
        assertThatThrownBy(() -> fluxo.registrarGasto(new RegistrarGasto(comando.id(), comando.data(), "Padaria",
                Dinheiro.de("13"), categoria, conta))).hasMessageContaining("outro conteúdo");
    }

    @Test
    void resumo_do_mes_e_o_mesmo_do_endpoint() throws Exception {
        var montador = new MontadorDeFluxo(jdbc);
        var conta = new ContaId(montador.conta());
        var categoria = new CategoriaId(montador.categoria());
        fluxo.registrarGasto(new RegistrarGasto(new GastoId(UUID.randomUUID()), LocalDate.of(2098, 5, 2), "Feira",
                Dinheiro.de("40"), categoria, conta));

        var resumo = fluxo.resumoDoMes(Competencia.de("2098-05"));
        var endpoint = json.readTree(ClienteDeTeste.autenticado(contexto, jdbc)
                .perform(get("/api/fluxo/resumo").param("competencia", "2098-05"))
                .andReturn().getResponse().getContentAsString());

        assertThat(resumo.totalGasto()).isEqualTo(Dinheiro.de("40"));
        assertThat(endpoint.get("totalGasto").asString()).isEqualTo(resumo.totalGasto().toString());
        assertThat(endpoint.get("sobra").asString()).isEqualTo(resumo.sobra().toString());
        assertThat(endpoint.get("categorias").get(0).get("categoria").asString())
                .isEqualTo(resumo.categorias().getFirst().categoria().valor().toString());
    }
}
