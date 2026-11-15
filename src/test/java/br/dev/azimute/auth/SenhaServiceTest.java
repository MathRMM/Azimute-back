package br.dev.azimute.auth;

import br.dev.azimute.auth.UsuarioRepository.UsuarioRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RF35, RN15: senha atual obrigatória, mínimo de 12 caracteres, revoga todos menos o aparelho da troca. */
class SenhaServiceTest {

    private static final UsuarioRow ADMIN = new UsuarioRow(UUID.randomUUID(), "admin", "$hash-atual");
    private static final DispositivoAutenticado ATUAL =
            new DispositivoAutenticado(new DispositivoId(UUID.randomUUID()), "Celular");

    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final DispositivoRepository dispositivos = mock(DispositivoRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final SenhaService servico = new SenhaService(usuarios, dispositivos, encoder);

    @BeforeEach
    void preparar() {
        when(usuarios.buscarUnico()).thenReturn(ADMIN);
        when(encoder.matches("atual-correta", "$hash-atual")).thenReturn(true);
        when(encoder.encode(any())).thenReturn("$hash-novo");
    }

    @Test
    void senha_atual_errada_falha_e_nada_muda() {
        assertThatThrownBy(() -> servico.trocar(ATUAL, "errada", "uma-senha-nova-longa"))
                .isInstanceOf(CredenciaisInvalidasException.class);

        verify(usuarios, never()).atualizarSenha(any(), any());
        verify(dispositivos, never()).revogarTodosExceto(any());
    }

    @Test
    void senha_nova_com_11_caracteres_e_curta_e_nada_muda() {
        assertThatThrownBy(() -> servico.trocar(ATUAL, "atual-correta", "12345678901"))
                .isInstanceOfSatisfying(SenhaService.SenhaCurtaException.class, e -> {
                    assertThat(e.codigo()).isEqualTo("SENHA_CURTA");
                    assertThat(e.detalhes()).containsEntry("minimo", 12);
                });

        verify(usuarios, never()).atualizarSenha(any(), any());
        verify(dispositivos, never()).revogarTodosExceto(any());
    }

    @Test
    void senha_nova_com_12_caracteres_grava_o_hash_e_revoga_os_outros_aparelhos() {
        servico.trocar(ATUAL, "atual-correta", "123456789012");

        verify(encoder).encode("123456789012");
        verify(usuarios).atualizarSenha(ADMIN.id(), "$hash-novo");
        verify(dispositivos).revogarTodosExceto(ATUAL.id());
    }
}
