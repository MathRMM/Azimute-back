package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.CategoriaId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O holerite do fluxo principal A do PRD, com valores fictícios (SEC03): salário 5.000, INSS 480, vale-transporte
 * 300, previdência básica 50 e voluntária 250, adiantamento 2.500, líquido 1.420 e FGTS do mês 400.
 */
final class HoleriteDeExemplo {

    static final Competencia OUTUBRO = Competencia.de("2026-10");
    static final CategoriaId TRANSPORTE = new CategoriaId(UUID.randomUUID());
    static final ConfiguracaoFolha CONFIGURACAO = new ConfiguracaoFolha(new ContaId(UUID.randomUUID()),
            new ContaId(UUID.randomUUID()), new ContaId(UUID.randomUUID()));

    static final Map<String, RubricaCatalogo> CATALOGO = Map.of(
            "0001", new RubricaCatalogo("0001", "Salário Mensal", NaturezaRubrica.PROVENTO, null, false),
            "/314", new RubricaCatalogo("/314", "INSS", NaturezaRubrica.INSS, null, false),
            "/507", new RubricaCatalogo("/507", "Vale-Transporte", NaturezaRubrica.GASTO, TRANSPORTE, false),
            "2363", new RubricaCatalogo("2363", "Prev Priv Bas Func", NaturezaRubrica.PREVIDENCIA_BASICA, null, false),
            "2364", new RubricaCatalogo("2364", "Prev Priv Vol Func", NaturezaRubrica.PREVIDENCIA_VOLUNTARIA, null,
                    false),
            "/B02", new RubricaCatalogo("/B02", "Decto Adto Salário", NaturezaRubrica.ADIANTAMENTO, null, false));

    private HoleriteDeExemplo() {
    }

    static List<RubricaLida> rubricas() {
        return List.of(
                vencimento("0001", "Salário Mensal", "5000"),
                new RubricaLida("/314", "INSS", new BigDecimal("14.00"), null, Dinheiro.de("480")),
                desconto("/507", "Vale-Transporte", "300"),
                desconto("2363", "Prev Priv Bas Func", "50"),
                desconto("2364", "Prev Priv Vol Func", "250"),
                desconto("/B02", "Decto Adto Salário", "2500"));
    }

    static BasesLidas bases() {
        return new BasesLidas(Dinheiro.de("5000"), Dinheiro.de("5000"), Dinheiro.de("5000"), Dinheiro.de("400"),
                Dinheiro.de("4170"));
    }

    static HoleriteLido lido() {
        return new HoleriteLido(OUTUBRO, rubricas(), Dinheiro.de("5000"), Dinheiro.de("3580"), Dinheiro.de("1420"),
                bases());
    }

    static Holerite holerite(HoleriteId id, String contrapartida) {
        return new Holerite(id, OUTUBRO, Holerite.Origem.MANUAL, "hash", Dinheiro.de("5000"), Dinheiro.de("3580"),
                Dinheiro.de("1420"), bases(), Dinheiro.de(contrapartida), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 30));
    }

    static RubricaLida vencimento(String codigo, String descricao, String valor) {
        return new RubricaLida(codigo, descricao, null, Dinheiro.de(valor), null);
    }

    static RubricaLida desconto(String codigo, String descricao, String valor) {
        return new RubricaLida(codigo, descricao, null, null, Dinheiro.de(valor));
    }
}
