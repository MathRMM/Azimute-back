package br.dev.azimute;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fronteiras entre módulos verificadas no build (RF04).
 */
class ModularidadeTest {

    private final ApplicationModules modulos = ApplicationModules.of(AzimuteApplication.class);

    @Test
    void fronteiras_entre_modulos_sao_respeitadas() {
        modulos.verify();
    }

    @Test
    void todos_os_modulos_do_roadmap_existem() {
        assertThat(modulos.stream().map(m -> m.getIdentifier().toString()))
                .contains("comum", "plataforma", "calculadora", "auth",
                        "contas", "fluxo", "cartoes", "reserva",
                        "treino", "saude", "sistema", "agenda", "integracao");
    }
}
