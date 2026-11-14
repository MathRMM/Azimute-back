package br.dev.azimute.auth;

import br.dev.azimute.comum.ErroDeNegocio;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RN16, RN17, T5: as janelas e os limites, sem banco (relógio fixo, repositório falso). */
class LimiteDeTentativasTest {

    private static final Instant AGORA = Instant.parse("2026-11-14T15:00:00Z");

    private final TentativaRepository tentativas = mock(TentativaRepository.class);
    private final LimiteDeTentativas limite = new LimiteDeTentativas(tentativas, Clock.fixed(AGORA, ZoneOffset.UTC));

    @Test
    void abaixo_dos_dois_limites_passa_e_consulta_as_janelas_certas() {
        when(tentativas.enesimaFalhaDoIp(any(), any(), any(), anyInt())).thenReturn(Optional.empty());
        when(tentativas.falhasGlobais(any(), any())).thenReturn(19L);

        assertThatCode(() -> limite.verificar("10.0.0.1")).doesNotThrowAnyException();

        verify(tentativas).enesimaFalhaDoIp("10.0.0.1", AGORA.minus(Duration.ofMinutes(15)), AGORA, 5);
        verify(tentativas).falhasGlobais(AGORA.minus(Duration.ofHours(1)), AGORA);
    }

    @Test
    void quinta_falha_do_ip_na_janela_bloqueia_ate_ela_sair_da_janela() {
        var quinta = AGORA.minus(Duration.ofMinutes(4));
        when(tentativas.enesimaFalhaDoIp(any(), any(), any(), anyInt())).thenReturn(Optional.of(quinta));

        assertThatThrownBy(() -> limite.verificar("10.0.0.1"))
                .isInstanceOfSatisfying(LimiteDeTentativas.LoginBloqueadoException.class, e -> {
                    assertThat(e.codigo()).isEqualTo("LOGIN_BLOQUEADO");
                    assertThat(e.tipo()).isEqualTo(ErroDeNegocio.Tipo.BLOQUEADO);
                    // 14:56Z + 15 min = 15:11Z, no fuso de São Paulo
                    assertThat(e.detalhes()).containsEntry("liberaEm", "2026-11-14T12:11-03:00");
                });
        verify(tentativas, never()).falhasGlobais(any(), any());
    }

    @Test
    void vinte_falhas_globais_bloqueiam_todos_sem_detalhes() {
        when(tentativas.enesimaFalhaDoIp(any(), any(), any(), anyInt())).thenReturn(Optional.empty());
        when(tentativas.falhasGlobais(any(), any())).thenReturn(20L);

        assertThatThrownBy(() -> limite.verificar("10.0.0.1"))
                .isInstanceOfSatisfying(LimiteDeTentativas.LoginBloqueadoGlobalException.class, e -> {
                    assertThat(e.codigo()).isEqualTo("LOGIN_BLOQUEADO_GLOBAL");
                    assertThat(e.tipo()).isEqualTo(ErroDeNegocio.Tipo.BLOQUEADO);
                    assertThat(e.detalhes()).isEmpty();
                });
    }

    @Test
    void registra_com_o_horario_do_relogio_injetado() {
        limite.registrar("10.0.0.1", true);
        limite.registrar("10.0.0.2", false);

        verify(tentativas).registrar("10.0.0.1", AGORA, true);
        verify(tentativas).registrar("10.0.0.2", AGORA, false);
    }
}
