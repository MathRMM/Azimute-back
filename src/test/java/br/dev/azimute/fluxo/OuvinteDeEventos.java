package br.dev.azimute.fluxo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ouve os eventos do {@code fluxo} só depois do commit, como os outros módulos vão ouvir (T13). As classes de teste
 * que importam esta configuração compartilham o mesmo contexto do Spring.
 */
@TestConfiguration(proxyBeanMethods = false)
class OuvinteDeEventos {

    @Bean
    Registro registro() {
        return new Registro();
    }

    /** Classe aberta, não record: o Spring cria um proxy do ouvinte. */
    static class Registro {
        private final List<Object> recebidos = new CopyOnWriteArrayList<>();

        List<Object> recebidos() {
            return recebidos;
        }

        @TransactionalEventListener
        void aoLancarGasto(GastoLancado evento) {
            recebidos.add(evento);
        }

        @TransactionalEventListener
        void aoRegistrarGanho(GanhoRegistrado evento) {
            recebidos.add(evento);
        }
    }
}
