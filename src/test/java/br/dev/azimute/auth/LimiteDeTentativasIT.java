package br.dev.azimute.auth;

import br.dev.azimute.ClienteDeTeste;
import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Força bruta inviável sem trancar o dono para fora (RF37, RN16–RN18, SEC08, T5).
 * Contexto próprio (relógio ajustável), com a tabela de tentativas limpa a cada teste.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RelogioAjustavel.Config.class})
class LimiteDeTentativasIT {

    private static final String SENHA = "senha-de-teste-bem-comprida";
    private static final String ERRADA = "senha-errada-bem-comprida";
    private static final Instant T0 = Instant.parse("2026-11-14T13:00:00Z");

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired RelogioAjustavel relogio;

    MockMvc anonimo;

    @BeforeEach
    void preparar() {
        anonimo = ClienteDeTeste.anonimo(contexto);
        jdbc.sql("DELETE FROM auth.tentativas_login").update();
        relogio.definir(T0);
    }

    ResultActions cadastrar(String ip, String login, String senha) throws Exception {
        return anonimo.perform(post("/api/auth/dispositivos")
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("login", login, "senha", senha, "nomeDispositivo", "X"))));
    }

    long tentativas() {
        return jdbc.sql("SELECT count(*) FROM auth.tentativas_login").query(Long.class).single();
    }

    @Test
    void cinco_falhas_do_ip_bloqueiam_a_sexta_mesmo_com_a_senha_certa_e_outro_ip_segue() throws Exception {
        for (int i = 0; i < 5; i++) {
            relogio.avancar(Duration.ofSeconds(10));
            cadastrar("10.0.0.1", "admin", ERRADA).andExpect(status().isUnauthorized());
        }

        // a 1ª falha foi em T0+10s; ela sai da janela de 15 min e o IP volta a poder tentar
        var liberaEm = T0.plusSeconds(10).plus(Duration.ofMinutes(15)).atZone(ZoneId.of("America/Sao_Paulo"))
                .toOffsetDateTime().toString();
        cadastrar("10.0.0.1", "admin", SENHA)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.erro").value("LOGIN_BLOQUEADO"))
                .andExpect(jsonPath("$.detalhes.liberaEm").value(liberaEm));

        cadastrar("10.0.0.2", "admin", SENHA).andExpect(status().isOk());
    }

    @Test
    void falhas_com_mais_de_15_minutos_nao_contam() throws Exception {
        for (int i = 0; i < 5; i++) {
            cadastrar("10.0.1.1", "admin", ERRADA).andExpect(status().isUnauthorized());
        }
        cadastrar("10.0.1.1", "admin", SENHA).andExpect(status().isTooManyRequests());

        relogio.avancar(Duration.ofMinutes(15).plusSeconds(1));
        cadastrar("10.0.1.1", "admin", SENHA).andExpect(status().isOk());
    }

    @Test
    void vinte_falhas_globais_bloqueiam_todos_mas_tokens_existentes_continuam() throws Exception {
        var token = ClienteDeTeste.novoDispositivo(jdbc, "Já cadastrado");
        for (int i = 0; i < 20; i++) {
            cadastrar("10.0.2." + (i / 4), "admin", ERRADA).andExpect(status().isUnauthorized());
        }

        cadastrar("10.0.9.9", "admin", SENHA)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.erro").value("LOGIN_BLOQUEADO_GLOBAL"));
        ClienteDeTeste.autenticadoCom(contexto, token).perform(get("/api/calculadora/historico"))
                .andExpect(status().isOk());

        relogio.avancar(Duration.ofHours(1).plusSeconds(1));
        cadastrar("10.0.9.9", "admin", SENHA).andExpect(status().isOk());
    }

    @Test
    void tentativa_bloqueada_nao_e_registrada() throws Exception {
        for (int i = 0; i < 5; i++) {
            cadastrar("10.0.3.1", "admin", ERRADA);
        }
        assertThat(tentativas()).isEqualTo(5);

        cadastrar("10.0.3.1", "admin", ERRADA).andExpect(status().isTooManyRequests());
        cadastrar("10.0.3.1", "admin", SENHA).andExpect(status().isTooManyRequests());
        assertThat(tentativas()).isEqualTo(5);
    }

    @Test
    void toda_tentativa_nao_bloqueada_gera_uma_linha_com_ip_e_resultado() throws Exception {
        cadastrar("10.0.4.1", "admin", SENHA).andExpect(status().isOk());
        cadastrar("10.0.4.2", "admin", ERRADA).andExpect(status().isUnauthorized());
        cadastrar("10.0.4.3", "fulano", SENHA).andExpect(status().isUnauthorized());

        var linhas = jdbc.sql("SELECT host(ip) AS ip, sucesso, created_at FROM auth.tentativas_login ORDER BY id")
                .query().listOfRows();
        assertThat(linhas).extracting(l -> l.get("ip")).containsExactly("10.0.4.1", "10.0.4.2", "10.0.4.3");
        assertThat(linhas).extracting(l -> l.get("sucesso")).containsExactly(true, false, false);
        assertThat(linhas).extracting(l -> ((java.sql.Timestamp) l.get("created_at")).toInstant()).containsOnly(T0);
    }

    /** RN18: sem o hash fictício, login inexistente responde em ~1 ms e senha errada em ~250 ms (BCrypt 12). */
    @Test
    void login_inexistente_e_senha_errada_levam_o_mesmo_tempo() throws Exception {
        cadastrar("10.0.5.250", "fulano", ERRADA); // aquecimento
        cadastrar("10.0.5.251", "admin", ERRADA);

        List<Long> inexistente = new ArrayList<>();
        List<Long> errada = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            jdbc.sql("DELETE FROM auth.tentativas_login").update(); // fica longe do limite global
            inexistente.add(medir(() -> cadastrar("10.0.5.1", "fulano", ERRADA)));
            errada.add(medir(() -> cadastrar("10.0.5.2", "admin", ERRADA)));
        }

        var a = mediana(inexistente);
        var b = mediana(errada);
        assertThat(Math.abs(a - b) / (double) Math.max(a, b))
                .as("medianas: inexistente %d ms, senha errada %d ms", a / 1_000_000, b / 1_000_000)
                .isLessThan(0.30);
    }

    interface Acao { void executar() throws Exception; }

    static long medir(Acao acao) throws Exception {
        var inicio = System.nanoTime();
        acao.executar();
        return System.nanoTime() - inicio;
    }

    static long mediana(List<Long> amostras) {
        var ordenadas = amostras.stream().sorted().toList();
        return ordenadas.get(ordenadas.size() / 2);
    }
}
