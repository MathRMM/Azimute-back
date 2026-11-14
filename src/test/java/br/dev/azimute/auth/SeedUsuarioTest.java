package br.dev.azimute.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** RF30, SEC04: sem usuário, a subida exige APP_SENHA_INICIAL com 12 caracteres ou mais. */
class SeedUsuarioTest {

    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    @Test
    void com_usuario_existente_nao_faz_nada_mesmo_sem_senha() {
        when(usuarios.existeAlgum()).thenReturn(true);

        new SeedUsuario(usuarios, encoder, null).run(null);

        verify(usuarios, never()).criar(any(), any());
        verifyNoInteractions(encoder);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void sem_usuario_e_sem_senha_a_subida_falha(String senha) {
        var seed = new SeedUsuario(usuarios, encoder, senha);

        assertThatThrownBy(() -> seed.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_SENHA_INICIAL não definida");
        verify(usuarios, never()).criar(any(), any());
    }

    @Test
    void senha_inicial_com_11_caracteres_e_recusada() {
        var seed = new SeedUsuario(usuarios, encoder, "12345678901");

        assertThatThrownBy(() -> seed.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pelo menos 12 caracteres");
        verify(usuarios, never()).criar(any(), any());
    }

    @Test
    void senha_inicial_valida_cria_o_admin_com_o_hash() {
        when(encoder.encode("123456789012")).thenReturn("$hash");

        new SeedUsuario(usuarios, encoder, "123456789012").run(null);

        verify(usuarios).criar("admin", "$hash");
    }
}
