package br.dev.azimute.calculadora.historico;

import br.dev.azimute.calculadora.core.JurosCompostos;
import br.dev.azimute.calculadora.core.TaxaMensal;
import br.dev.azimute.comum.Dinheiro;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recalcula com o core Java o que o cliente calculou no aparelho e rejeita divergências (RF28, D6).
 * O valor gravado continua sendo o enviado: é o número que o usuário viu na tela.
 */
@Component
class ConferenciaDeSnapshot {

    /** Tolerância da taxa recalculada a partir da parcela (proposta) e da taxa anual. */
    static final BigDecimal TOLERANCIA_TAXA = new BigDecimal("0.000001");

    /**
     * Tolerância da parcela de uma estimativa. O app calcula com a taxa da âncora em precisão total; o servidor
     * só conhece a taxa gravada com 8 casas. Essa diferença pode virar um centavo no arredondamento — e uma
     * rejeição indevida travaria a fila offline para sempre, porque ela não descarta itens.
     */
    static final Dinheiro TOLERANCIA_PARCELA_ESTIMATIVA = Dinheiro.de("0.01");

    /**
     * @param ancora a proposta âncora, obrigatória quando {@code dados} é uma estimativa (já validada)
     * @throws SnapshotDivergenteException com o valor enviado e o esperado de cada campo divergente
     */
    void conferir(DadosSimulacao dados, SimulacaoSalva ancora) {
        var divergencias = new LinkedHashMap<String, Object>();

        var financiado = dados.valorBem().menos(dados.entrada());
        var totalPago = dados.parcela().vezes(dados.prazo());
        var totalJuros = totalPago.menos(financiado);

        exigirIgual(divergencias, "valorFinanciado", dados.valorFinanciado(), financiado);
        exigirIgual(divergencias, "totalPago", dados.totalPago(), totalPago);
        exigirIgual(divergencias, "totalJuros", dados.totalJuros(), totalJuros);

        switch (dados.origem()) {
            case PROPOSTA -> {
                // Lança SEM_JUROS / TAXA_FORA_DA_FAIXA do próprio core quando for o caso.
                var taxa = JurosCompostos.taxaImplicita(financiado, dados.parcela(), dados.prazo()).fracao();
                exigirProximo(divergencias, "taxaMensal", dados.taxaMensal(), taxa);
            }
            case ESTIMATIVA -> {
                var taxaDaAncora = ancora.dados().taxaMensal();
                if (dados.taxaMensal().compareTo(taxaDaAncora) != 0) {
                    divergencias.put("taxaMensal", divergencia(dados.taxaMensal(), taxaDaAncora));
                }
                var parcela = JurosCompostos.parcela(financiado, new TaxaMensal(taxaDaAncora), dados.prazo());
                if (dados.parcela().menos(parcela).valor().abs().compareTo(TOLERANCIA_PARCELA_ESTIMATIVA.valor()) > 0) {
                    divergencias.put("parcela", divergencia(dados.parcela(), parcela));
                }
            }
        }

        var anual = new TaxaMensal(dados.taxaMensal()).anualizada().fracao();
        exigirProximo(divergencias, "taxaAnual", dados.taxaAnual(), anual);

        if (!divergencias.isEmpty()) {
            throw new SnapshotDivergenteException(divergencias);
        }
    }

    private static void exigirIgual(Map<String, Object> divergencias, String campo, Dinheiro enviado, Dinheiro esperado) {
        if (!enviado.equals(esperado)) {
            divergencias.put(campo, divergencia(enviado, esperado));
        }
    }

    private static void exigirProximo(Map<String, Object> divergencias, String campo, BigDecimal enviado, BigDecimal esperado) {
        if (enviado.subtract(esperado).abs().compareTo(TOLERANCIA_TAXA) > 0) {
            divergencias.put(campo, divergencia(enviado,
                    esperado.setScale(DadosSimulacao.ESCALA_TAXA, RoundingMode.HALF_EVEN)));
        }
    }

    private static Map<String, String> divergencia(Object enviado, Object esperado) {
        return Map.of("enviado", texto(enviado), "esperado", texto(esperado));
    }

    private static String texto(Object valor) {
        return valor instanceof BigDecimal decimal ? decimal.toPlainString() : valor.toString();
    }
}
