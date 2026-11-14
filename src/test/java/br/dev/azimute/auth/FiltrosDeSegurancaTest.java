package br.dev.azimute.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** RF32 e SEC12 sem subir o Spring: o que cada filtro deixa passar e o que barra. */
class FiltrosDeSegurancaTest {

    private final DispositivoRepository dispositivos = mock(DispositivoRepository.class);
    private final MockHttpServletResponse resposta = new MockHttpServletResponse();
    private final MockFilterChain cadeia = new MockFilterChain();

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
    }

    // --- TokenFilter -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "Basic YWRtaW46c2VuaGE=", "Bearer "})
    void cabecalho_ausente_ou_fora_do_formato_nao_autentica_nem_consulta_o_banco(String cabecalho) throws Exception {
        var requisicao = new MockHttpServletRequest("GET", "/api/x");
        if (!cabecalho.isEmpty()) requisicao.addHeader("Authorization", cabecalho);

        new TokenFilter(dispositivos).doFilter(requisicao, resposta, cadeia);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(cadeia.getRequest()).isSameAs(requisicao);
        verifyNoInteractions(dispositivos);
    }

    @Test
    void token_desconhecido_ou_revogado_nao_autentica_e_segue_a_cadeia() throws Exception {
        when(dispositivos.buscarAtivoPorHash(GeradorDeToken.hash("desconhecido"))).thenReturn(Optional.empty());
        var requisicao = new MockHttpServletRequest("GET", "/api/x");
        requisicao.addHeader("Authorization", "Bearer desconhecido");

        new TokenFilter(dispositivos).doFilter(requisicao, resposta, cadeia);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(cadeia.getRequest()).isSameAs(requisicao);
        verify(dispositivos, never()).registrarUso(any());
    }

    @Test
    void token_valido_autentica_o_dispositivo_e_registra_o_uso() throws Exception {
        var dispositivo = new DispositivoAutenticado(new DispositivoId(UUID.randomUUID()), "Celular");
        when(dispositivos.buscarAtivoPorHash(GeradorDeToken.hash("tok"))).thenReturn(Optional.of(dispositivo));
        var requisicao = new MockHttpServletRequest("GET", "/api/x");
        requisicao.addHeader("Authorization", "Bearer  tok ");

        new TokenFilter(dispositivos).doFilter(requisicao, resposta, cadeia);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(dispositivo);
        verify(dispositivos).registrarUso(dispositivo.id());
        assertThat(cadeia.getRequest()).isSameAs(requisicao);
    }

    // --- HttpsObrigatorioFilter --------------------------------------------------------------------------------

    private final RespostaDeErro erro = new RespostaDeErro(JsonMapper.builder().build());

    @Test
    void http_puro_fora_do_dev_e_rejeitado_com_403_sem_chegar_ao_controller() throws Exception {
        var requisicao = new MockHttpServletRequest("POST", "/api/auth/dispositivos");

        new HttpsObrigatorioFilter(true, erro).doFilter(requisicao, resposta, cadeia);

        assertThat(resposta.getStatus()).isEqualTo(403);
        assertThat(resposta.getContentAsString()).contains("\"erro\":\"HTTPS_OBRIGATORIO\"");
        assertThat(cadeia.getRequest()).isNull();
    }

    @Test
    void https_passa() throws Exception {
        var requisicao = new MockHttpServletRequest("POST", "/api/auth/dispositivos");
        requisicao.setSecure(true);

        new HttpsObrigatorioFilter(true, erro).doFilter(requisicao, resposta, cadeia);

        assertThat(cadeia.getRequest()).isSameAs(requisicao);
    }

    @Test
    void health_passa_por_http_para_o_healthcheck_interno() throws Exception {
        var requisicao = new MockHttpServletRequest("GET", HttpsObrigatorioFilter.HEALTH);

        new HttpsObrigatorioFilter(true, erro).doFilter(requisicao, resposta, cadeia);

        assertThat(cadeia.getRequest()).isSameAs(requisicao);
    }

    @Test
    void no_perfil_dev_http_passa() throws Exception {
        var requisicao = new MockHttpServletRequest("POST", "/api/auth/dispositivos");

        new HttpsObrigatorioFilter(false, erro).doFilter(requisicao, resposta, cadeia);

        assertThat(cadeia.getRequest()).isSameAs(requisicao);
    }
}
