package br.dev.azimute.auth;

import br.dev.azimute.ClienteDeTeste;
import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Troca de senha (RF35, RN15) e log sem segredo (SEC07, SEC08). Divide o contexto com os outros ITs, então
 * restaura a senha ao fim de cada teste e usa IPs próprios, para não pesar no limite de tentativas deles.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class TrocaDeSenhaIT {

    private static final String SENHA = "senha-de-teste-bem-comprida"; // src/test/resources/config/application.yml
    private static final String NOVA = "uma-senha-nova-e-comprida";
    private static final String PROTEGIDO = "/api/calculadora/historico";

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder encoder;

    String hashOriginal;

    @BeforeEach
    void guardarSenha() {
        hashOriginal = hashAtual();
    }

    @AfterEach
    void restaurarSenha() {
        jdbc.sql("UPDATE auth.usuarios SET senha_hash = :h WHERE login = 'admin'").param("h", hashOriginal).update();
    }

    String hashAtual() {
        return jdbc.sql("SELECT senha_hash FROM auth.usuarios WHERE login = 'admin'").query(String.class).single();
    }

    ResultActions trocar(MockMvc cliente, Map<String, String> corpo) throws Exception {
        return cliente.perform(post("/api/auth/senha").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    ResultActions cadastrar(String ip, String senha) throws Exception {
        return ClienteDeTeste.anonimo(contexto).perform(post("/api/auth/dispositivos")
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("login", "admin", "senha", senha, "nomeDispositivo", "Y"))));
    }

    static String ipUnico() {
        var b = UUID.randomUUID().getLeastSignificantBits();
        return "172.16." + (b & 0xff) + "." + ((b >> 8) & 0xff);
    }

    @Test
    void sem_senha_atual_ou_com_ela_errada_falha_e_nada_muda() throws Exception {
        var cliente = ClienteDeTeste.autenticado(contexto, jdbc);
        var outro = ClienteDeTeste.autenticadoCom(contexto, ClienteDeTeste.novoDispositivo(jdbc, "Outro"));

        trocar(cliente, Map.of("senhaNova", NOVA)).andExpect(status().is(422));
        trocar(cliente, Map.of("senhaAtual", "senha-errada-bem-comprida", "senhaNova", NOVA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.erro").value("CREDENCIAIS_INVALIDAS"));

        assertThat(hashAtual()).isEqualTo(hashOriginal);
        outro.perform(get(PROTEGIDO)).andExpect(status().isOk());
    }

    @Test
    void senha_nova_com_11_caracteres_e_curta() throws Exception {
        trocar(ClienteDeTeste.autenticado(contexto, jdbc), Map.of("senhaAtual", SENHA, "senhaNova", "12345678901"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("SENHA_CURTA"));
        assertThat(hashAtual()).isEqualTo(hashOriginal);
    }

    @Test
    void sucesso_mantem_o_dispositivo_atual_revoga_os_outros_e_aposenta_a_senha_antiga() throws Exception {
        var atual = ClienteDeTeste.autenticado(contexto, jdbc);
        var outro = ClienteDeTeste.autenticadoCom(contexto, ClienteDeTeste.novoDispositivo(jdbc, "Outro"));
        outro.perform(get(PROTEGIDO)).andExpect(status().isOk());

        trocar(atual, Map.of("senhaAtual", SENHA, "senhaNova", NOVA)).andExpect(status().isNoContent());

        atual.perform(get(PROTEGIDO)).andExpect(status().isOk());
        outro.perform(get(PROTEGIDO)).andExpect(status().isUnauthorized());
        assertThat(encoder.matches(NOVA, hashAtual())).isTrue();
        cadastrar(ipUnico(), SENHA).andExpect(status().isUnauthorized());
        cadastrar(ipUnico(), NOVA).andExpect(status().isOk());
    }

    @Test
    void o_log_nao_contem_senha_nem_token(CapturedOutput log) throws Exception {
        var ip = ipUnico();
        cadastrar(ip, "senha-errada-que-vaza").andExpect(status().isUnauthorized());
        var token = json.readTree(cadastrar(ipUnico(), SENHA).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asString();
        trocar(ClienteDeTeste.autenticadoCom(contexto, token), Map.of("senhaAtual", SENHA, "senhaNova", NOVA))
                .andExpect(status().isNoContent());

        assertThat(log.getAll())
                .contains(ip) // a falha é logada com o IP (SEC08)
                .doesNotContain("senha-errada-que-vaza")
                .doesNotContain(SENHA)
                .doesNotContain(NOVA)
                .doesNotContain(token);
    }
}
