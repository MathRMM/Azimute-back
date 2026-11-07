package br.dev.azimute;

import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fundação: banco migrado, uuidv7, fuso, eventos persistidos, health e imutabilidade.
 * Refs: RF05, RF07, RNF02, RNF03, RNF05, SEC09.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, FundacaoIT.EventoDeTesteConfig.class})
class FundacaoIT {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ApplicationEventPublisher eventos;
    @Autowired TransactionTemplate transacao;
    @Autowired JsonMapper jsonMapper;
    @Autowired org.springframework.web.context.WebApplicationContext contexto;

    @Test
    void flyway_aplica_a_migration_inicial() {
        var sucesso = jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '001'")
                .query(Boolean.class).single();
        assertThat(sucesso).isTrue();
    }

    @Test
    void schemas_dos_modulos_existem() {
        var schemas = jdbc.sql("SELECT schema_name FROM information_schema.schemata")
                .query(String.class).list();
        assertThat(schemas).contains("calculadora", "auth");
    }

    @Test
    void uuidv7_nativo_gera_uuid_versao_7() {
        var id = jdbc.sql("SELECT uuidv7()").query(UUID.class).single();
        assertThat(id.version()).isEqualTo(7);
    }

    @Test
    void fuso_e_sao_paulo_na_jvm_e_na_conexao() {
        assertThat(ZoneId.systemDefault()).isEqualTo(SAO_PAULO);
        var fusoDaConexao = jdbc.sql("SELECT current_setting('TimeZone')").query(String.class).single();
        assertThat(fusoDaConexao).isEqualTo("America/Sao_Paulo");
    }

    @Test
    void evento_publicado_em_transacao_fica_registrado() {
        var marcador = UUID.randomUUID().toString();

        transacao.executeWithoutResult(s -> eventos.publishEvent(new EventoDeTeste(marcador)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var registrados = jdbc.sql("""
                            SELECT count(*) FROM event_publication
                            WHERE event_type = :tipo AND serialized_event LIKE :marcador
                            """)
                    .param("tipo", EventoDeTeste.class.getName())
                    .param("marcador", "%" + marcador + "%")
                    .query(Long.class).single();
            assertThat(registrados).isEqualTo(1);
        });
    }

    @Test
    void health_e_publico_e_sem_detalhes() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void jackson_da_aplicacao_usa_o_modulo_do_kernel() {
        assertThat(jsonMapper.writeValueAsString(Dinheiro.de("17430"))).isEqualTo("\"17430.00\"");
    }

    @Test
    void rota_inexistente_responde_no_formato_de_erro_da_api() throws Exception {
        ClienteDeTeste.autenticado(contexto, jdbc).perform(get("/api/nao-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("ROTA_NAO_ENCONTRADA"))
                .andExpect(jsonPath("$.mensagem").exists());
    }

    @Test
    void outros_endpoints_do_actuator_nao_sao_expostos() throws Exception {
        ClienteDeTeste.autenticado(contexto, jdbc).perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    @Test
    void trigger_impedir_alteracao_bloqueia_update() {
        transacao.executeWithoutResult(s -> {
            jdbc.sql("CREATE TEMP TABLE fato_teste (id INT PRIMARY KEY, valor TEXT) ON COMMIT DROP").update();
            jdbc.sql("""
                    CREATE TRIGGER fato_teste_imutavel BEFORE UPDATE OR DELETE ON fato_teste
                    FOR EACH ROW EXECUTE FUNCTION impedir_alteracao()
                    """).update();
            jdbc.sql("INSERT INTO fato_teste VALUES (1, 'original')").update();

            assertThatThrownBy(() -> jdbc.sql("UPDATE fato_teste SET valor = 'novo'").update())
                    .hasMessageContaining("Registro imutável em fato_teste");
            s.setRollbackOnly();
        });
    }

    record EventoDeTeste(String marcador) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class EventoDeTesteConfig {
        @Bean
        OuvinteDeTeste ouvinteDeTeste() {
            return new OuvinteDeTeste();
        }
    }

    static class OuvinteDeTeste {
        @ApplicationModuleListener
        void ao(EventoDeTeste evento) {
            // Só precisa existir para o registro de publicação ser criado.
        }
    }
}
