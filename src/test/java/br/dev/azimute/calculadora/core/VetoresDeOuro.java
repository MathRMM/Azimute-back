package br.dev.azimute.calculadora.core;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;

/** Leitura de {@code vetores-juros.json}, a fonte de verdade compartilhada com o app (RF17). */
final class VetoresDeOuro {

    record VetorTaxa(String caso, String pv, String pmt, int n, String taxaMensal, String toleranciaTaxa,
                     String taxaAnualPercentual1Casa, String totalPago, String totalJuros) {
        BigDecimal taxa() {
            return new BigDecimal(taxaMensal);
        }

        BigDecimal tolerancia() {
            return new BigDecimal(toleranciaTaxa);
        }

        @Override
        public String toString() {
            return caso;
        }
    }

    record VetorComparacao(String caso, String precoAVista, String entrada, String parcela, int prazo,
                           String rendimento, String custoTotalFinanciado, String custoTotalAVista,
                           String rendimentoDoDinheiroGuardado, String delta, String taxaFinanciamento,
                           String toleranciaTaxa) {
        @Override
        public String toString() {
            return caso;
        }
    }

    record Arquivo(List<VetorTaxa> taxaImplicita, List<VetorComparacao> comparacao) {}

    private VetoresDeOuro() {}

    static List<VetorTaxa> taxaImplicita() {
        return ler().taxaImplicita();
    }

    static List<VetorComparacao> comparacao() {
        return ler().comparacao();
    }

    private static Arquivo ler() {
        try (InputStream in = VetoresDeOuro.class.getResourceAsStream("/vetores-juros.json")) {
            if (in == null) {
                throw new IllegalStateException("vetores-juros.json não encontrado no classpath de teste");
            }
            return JsonMapper.builder().disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build().readValue(in, Arquivo.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
