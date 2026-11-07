package br.dev.azimute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class AzimuteApplication {

    /** Fuso fixo: o servidor de VPS vem em UTC e um lançamento das 22h cairia no dia seguinte (RNF05). */
    public static final String FUSO = "America/Sao_Paulo";

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(FUSO));
        SpringApplication.run(AzimuteApplication.class, args);
    }
}
