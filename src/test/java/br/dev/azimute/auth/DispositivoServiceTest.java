package br.dev.azimute.auth;

import br.dev.azimute.auth.UsuarioRepository.UsuarioRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** RF31, RF37, RN16, RN18: a ordem limite → BCrypt → registro, e a resposta igual para os dois tipos de falha. */
class DispositivoServiceTest {

    private static final String HASH_FICTICIO = "$hash-ficticio";
    private static final String HASH_REAL = "$hash-real";
    private static final UsuarioRow ADMIN = new UsuarioRow(UUID.randomUUID(), "admin", HASH_REAL);
    private static final String IP = "10.0.0.1";

    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final DispositivoRepository dispositivos = mock(DispositivoRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final LimiteDeTentativas limite = mock(LimiteDeTentativas.class);
    private DispositivoService servico;

    @BeforeEach
    void preparar() {
        when(encoder.encode(anyString())).thenReturn(HASH_FICTICIO);
        servico = new DispositivoService(usuarios, dispositivos, encoder, limite);
    }

    @Test
    void ip_bloqueado_nao_roda_o_bcrypt_nem_registra_a_tentativa() {
        doThrow(new LimiteDeTentativas.LoginBloqueadoGlobalException()).when(limite).verificar(IP);

        assertThatThrownBy(() -> servico.cadastrar("admin", "senha", "Celular", IP))
                .isInstanceOf(LimiteDeTentativas.LoginBloqueadoGlobalException.class);

        verify(encoder, never()).matches(anyString(), anyString());
        verify(limite, never()).registrar(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        verifyNoInteractions(usuarios, dispositivos);
    }

    @Test
    void login_inexistente_paga_o_bcrypt_contra_o_hash_ficticio_e_registra_falha() {
        when(usuarios.buscarPorLogin("fulano")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.cadastrar("fulano", "senha", "Celular", IP))
                .isInstanceOf(CredenciaisInvalidasException.class);

        var ordem = inOrder(limite, encoder);
        ordem.verify(limite).verificar(IP);
        ordem.verify(encoder).matches("senha", HASH_FICTICIO);
        ordem.verify(limite).registrar(IP, false);
        verifyNoInteractions(dispositivos);
    }

    @Test
    void login_inexistente_falha_mesmo_que_a_senha_confira_com_o_hash_ficticio() {
        when(usuarios.buscarPorLogin("fulano")).thenReturn(Optional.empty());
        when(encoder.matches("senha", HASH_FICTICIO)).thenReturn(true);

        assertThatThrownBy(() -> servico.cadastrar("fulano", "senha", "Celular", IP))
                .isInstanceOf(CredenciaisInvalidasException.class);

        verify(limite).registrar(IP, false);
        verifyNoInteractions(dispositivos);
    }

    @Test
    void senha_errada_registra_falha_e_responde_igual_ao_login_inexistente() {
        when(usuarios.buscarPorLogin("admin")).thenReturn(Optional.of(ADMIN));
        when(encoder.matches("errada", HASH_REAL)).thenReturn(false);

        assertThatThrownBy(() -> servico.cadastrar("admin", "errada", "Celular", IP))
                .isInstanceOf(CredenciaisInvalidasException.class)
                .hasMessage("Login ou senha inválidos");

        verify(limite).registrar(IP, false);
        verifyNoInteractions(dispositivos);
        verify(usuarios, never()).registrarAcesso(ADMIN.id());
    }

    @Test
    void sucesso_registra_acerto_grava_so_o_hash_do_token_e_devolve_o_token_uma_vez() {
        var id = new DispositivoId(UUID.randomUUID());
        when(usuarios.buscarPorLogin("admin")).thenReturn(Optional.of(ADMIN));
        when(encoder.matches("certa", HASH_REAL)).thenReturn(true);
        when(dispositivos.criar(eq("Celular"), anyString())).thenReturn(id);

        var cadastro = servico.cadastrar("admin", "certa", "  Celular  ", IP);

        var hash = ArgumentCaptor.forClass(String.class);
        verify(dispositivos).criar(eq("Celular"), hash.capture());
        assertThat(cadastro.id()).isEqualTo(id);
        assertThat(hash.getValue()).isEqualTo(GeradorDeToken.hash(cadastro.token())).isNotEqualTo(cadastro.token());
        verify(limite).registrar(IP, true);
        verify(usuarios).registrarAcesso(ADMIN.id());
    }
}
