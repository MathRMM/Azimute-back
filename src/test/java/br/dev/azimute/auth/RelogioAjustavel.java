package br.dev.azimute.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Relógio que o teste avança à mão, para provar a janela do limite de tentativas sem esperar 15 minutos. */
final class RelogioAjustavel extends Clock {

    private volatile Instant agora = Instant.now();

    void definir(Instant instante) {
        agora = instante;
    }

    void avancar(Duration duracao) {
        agora = agora.plus(duracao);
    }

    @Override
    public Instant instant() {
        return agora;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("America/Sao_Paulo");
    }

    @Override
    public Clock withZone(ZoneId zona) {
        throw new UnsupportedOperationException();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        RelogioAjustavel relogioAjustavel() {
            return new RelogioAjustavel();
        }
    }
}
