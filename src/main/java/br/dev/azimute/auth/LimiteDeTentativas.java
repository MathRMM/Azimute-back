package br.dev.azimute.auth;

import br.dev.azimute.comum.ErroDeNegocio;
import br.dev.azimute.comum.Fuso;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Limite de tentativas do cadastro de dispositivo (RN16, RN17).
 * <p>
 * Só o cadastro é limitado: os tokens já emitidos não passam por aqui, então quem ataca não tranca o dono para
 * fora. A verificação vem <b>antes</b> do BCrypt, e tentativa bloqueada não é registrada (T5): quem continua
 * insistindo não estende o próprio bloqueio para sempre.
 */
@Component
class LimiteDeTentativas {

    static final int FALHAS_POR_IP = 5;
    static final Duration JANELA_POR_IP = Duration.ofMinutes(15);
    static final int FALHAS_GLOBAIS = 20;
    static final Duration JANELA_GLOBAL = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(LimiteDeTentativas.class);

    private final TentativaRepository tentativas;
    private final Clock relogio;

    LimiteDeTentativas(TentativaRepository tentativas, Clock relogio) {
        this.tentativas = tentativas;
        this.relogio = relogio;
    }

    /** Lança {@link LoginBloqueadoException} ou {@link LoginBloqueadoGlobalException} se o limite foi atingido. */
    void verificar(String ip) {
        var agora = relogio.instant();
        var quinta = tentativas.enesimaFalhaDoIp(ip, agora.minus(JANELA_POR_IP), agora, FALHAS_POR_IP);
        if (quinta.isPresent()) {
            var liberaEm = quinta.get().plus(JANELA_POR_IP);
            log.warn("Cadastro de dispositivo bloqueado para o IP {} até {}", ip, liberaEm);
            throw new LoginBloqueadoException(liberaEm);
        }
        if (tentativas.falhasGlobais(agora.minus(JANELA_GLOBAL), agora) >= FALHAS_GLOBAIS) {
            log.warn("Cadastro de dispositivo bloqueado para todos: {} falhas na última hora (IP {})",
                    FALHAS_GLOBAIS, ip);
            throw new LoginBloqueadoGlobalException();
        }
    }

    void registrar(String ip, boolean sucesso) {
        tentativas.registrar(ip, relogio.instant(), sucesso);
    }

    static final class LoginBloqueadoException extends ErroDeNegocio {
        LoginBloqueadoException(Instant liberaEm) {
            super("LOGIN_BLOQUEADO", "Muitas tentativas deste endereço. Tente de novo mais tarde.", Tipo.BLOQUEADO,
                    Map.of("liberaEm", Fuso.naApi(liberaEm).toString()));
        }
    }

    static final class LoginBloqueadoGlobalException extends ErroDeNegocio {
        LoginBloqueadoGlobalException() {
            super("LOGIN_BLOQUEADO_GLOBAL",
                    "Cadastro de dispositivos suspenso temporariamente. Os aparelhos já cadastrados continuam funcionando.",
                    Tipo.BLOQUEADO);
        }
    }
}
