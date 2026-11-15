package br.dev.azimute.plataforma.web;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.module.SimpleModule;

/**
 * (De)serialização dos tipos do kernel (decisão T2).
 * <p>
 * Dinheiro sai como string decimal ({@code "17430.00"}), não como número: o cliente Kotlin tenderia a ler
 * número JSON como {@code Double}. Na entrada, aceita string ou número, sempre lido como decimal exato.
 * Competência sai e entra como {@code "yyyy-MM"}.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonKernelConfig {

    @Bean
    public SimpleModule moduloDoKernel() {
        return new SimpleModule("azimute-kernel")
                .addSerializer(Dinheiro.class, new DinheiroSerializer())
                .addDeserializer(Dinheiro.class, new DinheiroDeserializer())
                .addSerializer(Competencia.class, new CompetenciaSerializer())
                .addDeserializer(Competencia.class, new CompetenciaDeserializer());
    }

    static final class DinheiroSerializer extends ValueSerializer<Dinheiro> {
        @Override
        public void serialize(Dinheiro valor, JsonGenerator gerador, SerializationContext contexto) {
            gerador.writeString(valor.valor().toPlainString());
        }
    }

    static final class DinheiroDeserializer extends ValueDeserializer<Dinheiro> {
        @Override
        public Dinheiro deserialize(JsonParser parser, DeserializationContext contexto) {
            var token = parser.currentToken();
            if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                return new Dinheiro(parser.getDecimalValue());
            }
            var texto = parser.getString();
            try {
                return Dinheiro.de(texto.trim());
            } catch (NumberFormatException e) {
                throw InvalidFormatException.from(parser,
                        "Valor monetário inválido, use decimal com ponto (ex.: \"17430.00\")", texto, Dinheiro.class);
            }
        }
    }

    static final class CompetenciaSerializer extends ValueSerializer<Competencia> {
        @Override
        public void serialize(Competencia valor, JsonGenerator gerador, SerializationContext contexto) {
            gerador.writeString(valor.toString());
        }
    }

    static final class CompetenciaDeserializer extends ValueDeserializer<Competencia> {
        @Override
        public Competencia deserialize(JsonParser parser, DeserializationContext contexto) {
            var texto = parser.getString();
            try {
                return Competencia.de(texto);
            } catch (IllegalArgumentException e) {
                throw InvalidFormatException.from(parser,
                        "Competência deve estar no formato " + Competencia.FORMATO, texto, Competencia.class);
            }
        }
    }
}
