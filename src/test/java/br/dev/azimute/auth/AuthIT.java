package br.dev.azimute.auth;

import br.dev.azimute.ClienteDeTeste;
import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Senha uma vez, token por dispositivo depois. Sem perfil ativo (regras seguras), sempre por HTTPS.
 * Refs: RF31–RF34, RF36, SEC01–SEC03, SEC09, SEC11.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class AuthIT {

    private static final String SENHA = "senha-de-teste-bem-comprida"; // src/test/resources/config/application.yml
    private static final String PROTEGIDO = "/api/calculadora/historico";
    private static final String DISPOSITIVOS = "/api/auth/dispositivos";

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    MockMvc anonimo;

    @BeforeEach
    void preparar() {
        anonimo = ClienteDeTeste.anonimo(contexto);
    }

    ResultActions cadastrar(String login, String senha, String nome) throws Exception {
        return anonimo.perform(post(DISPOSITIVOS).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("login", login, "senha", senha, "nomeDispositivo", nome))));
    }

    String tokenDe(ResultActions resposta) throws Exception {
        return json.readTree(resposta.andReturn().getResponse().getContentAsString()).get("token").asString();
    }

    // ---- token ----

    @Test
    void sem_token_e_401_no_formato_de_erro() throws Exception {
        anonimo.perform(get(PROTEGIDO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.erro").value("NAO_AUTENTICADO"))
                .andExpect(jsonPath("$.mensagem").exists())
                .andExpect(jsonPath("$.detalhes").isMap());
    }

    @Test
    void token_invalido_e_401() throws Exception {
        ClienteDeTeste.autenticadoCom(contexto, "token-que-nao-existe").perform(get(PROTEGIDO))
                .andExpect(status().isUnauthorized());
        anonimo.perform(get(PROTEGIDO).header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46eA=="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void token_valido_passa() throws Exception {
        ClienteDeTeste.autenticado(contexto, jdbc).perform(get(PROTEGIDO)).andExpect(status().isOk());
    }

    @Test
    void token_revogado_e_401() throws Exception {
        var token = ClienteDeTeste.novoDispositivo(jdbc, "revogado");
        jdbc.sql("UPDATE auth.dispositivos SET revogado_em = now() WHERE token_hash = :h")
                .param("h", ClienteDeTeste.sha256(token)).update();

        ClienteDeTeste.autenticadoCom(contexto, token).perform(get(PROTEGIDO)).andExpect(status().isUnauthorized());
    }

    // ---- cadastro de dispositivo ----

    @Test
    void cadastro_devolve_o_token_e_o_banco_guarda_so_o_hash() throws Exception {
        var resposta = cadastrar("admin", SENHA, "S25 Ultra")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.token").exists());
        var token = tokenDe(resposta);

        assertThat(token).hasSizeGreaterThanOrEqualTo(43); // 256 bits em base64url
        var linha = jdbc.sql("SELECT nome, token_hash FROM auth.dispositivos WHERE token_hash = :h")
                .param("h", ClienteDeTeste.sha256(token)).query().singleRow();
        assertThat((String) linha.get("token_hash")).matches("[0-9a-f]{64}").isNotEqualTo(token);
        assertThat(jdbc.sql("SELECT count(*) FROM auth.dispositivos WHERE token_hash = :t OR nome = :t")
                .param("t", token).query(Long.class).single()).isZero();

        ClienteDeTeste.autenticadoCom(contexto, token).perform(get(PROTEGIDO)).andExpect(status().isOk());
    }

    @Test
    void cadastro_atualiza_o_ultimo_acesso_do_usuario() throws Exception {
        var antes = Instant.now().minusSeconds(1);
        cadastrar("admin", SENHA, "Notebook").andExpect(status().isOk());
        var ultimo = jdbc.sql("SELECT ultimo_acesso FROM auth.usuarios WHERE login = 'admin'")
                .query(java.sql.Timestamp.class).single().toInstant();
        assertThat(ultimo).isAfter(antes);
    }

    @Test
    void senha_errada_e_login_inexistente_respondem_igual() throws Exception {
        var senhaErrada = cadastrar("admin", "senha-errada-bem-comprida", "X")
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        var loginInexistente = cadastrar("fulano", SENHA, "X")
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(loginInexistente).isEqualTo(senhaErrada).contains("CREDENCIAIS_INVALIDAS");
    }

    @Test
    void cadastro_sem_campos_obrigatorios_e_invalido() throws Exception {
        anonimo.perform(post(DISPOSITIVOS).contentType(MediaType.APPLICATION_JSON).content("{\"login\":\"admin\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.senha").exists())
                .andExpect(jsonPath("$.detalhes.campos.nomeDispositivo").exists());
    }

    // ---- listagem e revogação ----

    @Test
    void lista_dispositivos_ativos_sem_expor_o_hash() throws Exception {
        var token = ClienteDeTeste.novoDispositivo(jdbc, "Este aparelho " + UUID.randomUUID());
        var cliente = ClienteDeTeste.autenticadoCom(contexto, token);

        var corpo = cliente.perform(get(DISPOSITIVOS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].nome").exists())
                .andExpect(jsonPath("$[0].criadoEm").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(corpo).doesNotContain("token").doesNotContain("hash").doesNotContain(ClienteDeTeste.sha256(token));
        var atuais = json.readTree(corpo).valueStream().filter(n -> n.get("atual").asBoolean()).toList();
        assertThat(atuais).hasSize(1);
        assertThat(atuais.getFirst().get("nome").asString()).startsWith("Este aparelho");
    }

    @Test
    void revogar_outro_dispositivo_corta_o_acesso_dele_na_hora() throws Exception {
        var meu = ClienteDeTeste.autenticado(contexto, jdbc);
        var tokenPerdido = ClienteDeTeste.novoDispositivo(jdbc, "Celular perdido");
        var perdido = ClienteDeTeste.autenticadoCom(contexto, tokenPerdido);
        perdido.perform(get(PROTEGIDO)).andExpect(status().isOk());

        var id = jdbc.sql("SELECT id FROM auth.dispositivos WHERE token_hash = :h")
                .param("h", ClienteDeTeste.sha256(tokenPerdido)).query(UUID.class).single();
        meu.perform(delete(DISPOSITIVOS + "/" + id)).andExpect(status().isNoContent());

        perdido.perform(get(PROTEGIDO)).andExpect(status().isUnauthorized());
        meu.perform(get(PROTEGIDO)).andExpect(status().isOk());
        meu.perform(get(DISPOSITIVOS))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]", hasSize(0)));
        meu.perform(delete(DISPOSITIVOS + "/" + id)).andExpect(status().isNoContent());
    }

    @Test
    void revogar_o_proprio_dispositivo_tambem_vale() throws Exception {
        var token = ClienteDeTeste.novoDispositivo(jdbc, "Eu mesmo");
        var cliente = ClienteDeTeste.autenticadoCom(contexto, token);
        var id = jdbc.sql("SELECT id FROM auth.dispositivos WHERE token_hash = :h")
                .param("h", ClienteDeTeste.sha256(token)).query(UUID.class).single();

        cliente.perform(delete(DISPOSITIVOS + "/" + id)).andExpect(status().isNoContent());
        cliente.perform(get(PROTEGIDO)).andExpect(status().isUnauthorized());
    }

    // ---- último uso (RF36) ----

    @Test
    void ultimo_uso_e_gravado_no_maximo_uma_vez_por_minuto() throws Exception {
        var token = ClienteDeTeste.novoDispositivo(jdbc, "Uso");
        var cliente = ClienteDeTeste.autenticadoCom(contexto, token);
        var consulta = "SELECT ultimo_uso FROM auth.dispositivos WHERE token_hash = '" + ClienteDeTeste.sha256(token) + "'";

        cliente.perform(get(PROTEGIDO)).andExpect(status().isOk());
        var primeiro = jdbc.sql(consulta).query(java.sql.Timestamp.class).single();
        assertThat(primeiro).isNotNull();

        cliente.perform(get(PROTEGIDO)).andExpect(status().isOk());
        assertThat(jdbc.sql(consulta).query(java.sql.Timestamp.class).single()).isEqualTo(primeiro);
    }

    // ---- sessão e cabeçalhos (SEC03, SEC11) ----

    @Test
    void nenhuma_resposta_cria_sessao() throws Exception {
        var resposta = cadastrar("admin", SENHA, "Sem sessão").andExpect(status().isOk()).andReturn().getResponse();
        assertThat(resposta.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(resposta.getCookie("JSESSIONID")).isNull();

        ClienteDeTeste.autenticado(contexto, jdbc).perform(get(PROTEGIDO))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void respostas_trazem_nosniff_e_hsts() throws Exception {
        ClienteDeTeste.autenticado(contexto, jdbc).perform(get(PROTEGIDO))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Strict-Transport-Security", org.hamcrest.Matchers.containsString("max-age=31536000")));
    }

    // ---- actuator (SEC09) ----

    @Test
    void health_e_publico_e_o_resto_do_actuator_exige_token() throws Exception {
        anonimo.perform(get("/actuator/health")).andExpect(status().isOk());
        anonimo.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        anonimo.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void callback_do_google_nao_e_aberto_nesta_fase() throws Exception {
        anonimo.perform(get("/api/integracao/google/callback")).andExpect(status().isUnauthorized());
    }
}
