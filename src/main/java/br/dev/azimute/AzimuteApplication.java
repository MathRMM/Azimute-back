package br.dev.azimute;

import br.dev.azimute.comum.Fuso;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class AzimuteApplication {

    /** Fuso fixo: o servidor de VPS vem em UTC e um lançamento das 22h cairia no dia seguinte (RNF05). */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(Fuso.ZONA));
        SpringApplication.run(AzimuteApplication.class, args);
    }
}
