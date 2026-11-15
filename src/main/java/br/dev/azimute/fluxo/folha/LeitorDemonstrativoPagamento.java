package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Layout "Demonstrativo de Pagamento" do empregador atual (RNF03): texto selecionável em largura fixa, colunas
 * separadas por barras. A leitura é pela estrutura das linhas, não pela posição na página:
 * <ul>
 *   <li>rubrica: {@code |código|descrição|referência|vencimentos|descontos|};</li>
 *   <li>totais: a primeira linha com dois valores nas colunas de vencimentos e descontos;</li>
 *   <li>líquido: a linha com a seta {@code =======>};</li>
 *   <li>bases: cada valor termina na coluna em que termina o seu rótulo.</li>
 * </ul>
 * O PDF é lido como dado (SEC06): nada nele é executado, cifrado é recusado e há limite de páginas. Nenhum texto do
 * PDF vai para o log nem para o erro (SEC02).
 */
@Component
class LeitorDemonstrativoPagamento implements LeitorDeHolerite {

    static final int MAXIMO_DE_PAGINAS = 2;

    private static final String CABECALHO = "D E M O N S T R A T I V O";
    private static final List<String> MESES = List.of("Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
            "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro");
    private static final Pattern COMPETENCIA = Pattern.compile("\\b(" + String.join("|", MESES) + ")\\s+(\\d{4})\\b");
    private static final Pattern CODIGO = Pattern.compile("[/0-9A-Z]+");
    private static final Pattern NUMERO = Pattern.compile("\\d{1,3}(\\.\\d{3})*,\\d{2}");
    private static final List<Pattern> ROTULOS_DAS_BASES = List.of(Pattern.compile("Sal\\S* Base"),
            Pattern.compile("Base INSS"), Pattern.compile("Base FGTS"), Pattern.compile("FGTS M\\S*"),
            Pattern.compile("Base IRRF"));

    @Override
    public HoleriteLido ler(byte[] pdf) {
        var linhas = linhas(pdf);
        if (linhas.stream().noneMatch(l -> l.contains(CABECALHO))) {
            throw new HoleriteIlegivelException("layout");
        }
        var totais = totais(linhas);
        return new HoleriteLido(competencia(linhas), rubricas(linhas), totais[0], totais[1], liquido(linhas),
                bases(linhas));
    }

    private static List<String> linhas(byte[] pdf) {
        try (var documento = Loader.loadPDF(pdf)) {
            if (documento.isEncrypted()) {
                throw new HoleriteIlegivelException("cifrado");
            }
            if (documento.getNumberOfPages() > MAXIMO_DE_PAGINAS) {
                throw new HoleriteIlegivelException("paginas");
            }
            var extrator = new PDFTextStripper();
            extrator.setSortByPosition(true);
            return extrator.getText(documento).lines().toList();
        } catch (InvalidPasswordException e) {
            throw new HoleriteIlegivelException("cifrado");
        } catch (IOException e) {
            throw new HoleriteIlegivelException("pdf");
        }
    }

    private static Competencia competencia(List<String> linhas) {
        for (var linha : linhas) {
            var achado = COMPETENCIA.matcher(linha);
            if (achado.find()) {
                return new Competencia(YearMonth.of(Integer.parseInt(achado.group(2)),
                        MESES.indexOf(achado.group(1)) + 1));
            }
        }
        throw new HoleriteIlegivelException("competencia");
    }

    /** Linhas de rubrica com código; as vazias, os traços e o cabeçalho da tabela ficam de fora. */
    private static List<RubricaLida> rubricas(List<String> linhas) {
        var rubricas = new ArrayList<RubricaLida>();
        for (var linha : linhas) {
            var colunas = linha.split("\\|", -1);
            if (colunas.length != 7 || !CODIGO.matcher(colunas[1].strip()).matches()) {
                continue;
            }
            var vencimento = dinheiro(colunas[4], "rubricas");
            var desconto = dinheiro(colunas[5], "rubricas");
            if ((vencimento == null) == (desconto == null)) {
                throw new HoleriteIlegivelException("rubricas");
            }
            var referencia = numero(colunas[3], "rubricas");
            rubricas.add(new RubricaLida(colunas[1].strip(), colunas[2].strip(), referencia, vencimento, desconto));
        }
        if (rubricas.isEmpty()) {
            throw new HoleriteIlegivelException("rubricas");
        }
        return rubricas;
    }

    private static Dinheiro[] totais(List<String> linhas) {
        for (var linha : linhas) {
            var colunas = linha.split("\\|", -1);
            if (colunas.length == 6 && !colunas[2].isBlank()) {
                var vencimentos = dinheiro(colunas[3], "totais");
                var descontos = dinheiro(colunas[4], "totais");
                if (vencimentos != null && descontos != null) {
                    return new Dinheiro[]{vencimentos, descontos};
                }
            }
        }
        throw new HoleriteIlegivelException("totais");
    }

    private static Dinheiro liquido(List<String> linhas) {
        for (var linha : linhas) {
            var colunas = linha.split("\\|", -1);
            if (linha.contains("=======>") && colunas.length == 5) {
                var liquido = dinheiro(colunas[3], "liquido");
                if (liquido != null) {
                    return liquido;
                }
            }
        }
        throw new HoleriteIlegivelException("liquido");
    }

    /** Cada valor termina na coluna em que termina o rótulo dele, na linha de baixo. */
    private static BasesLidas bases(List<String> linhas) {
        for (int i = 0; i + 1 < linhas.size(); i++) {
            var cabecalho = linhas.get(i);
            if (!cabecalho.contains("Base INSS")) {
                continue;
            }
            var valores = linhas.get(i + 1);
            var lidos = new Dinheiro[ROTULOS_DAS_BASES.size()];
            var inicio = 0;
            for (int r = 0; r < lidos.length; r++) {
                var rotulo = ROTULOS_DAS_BASES.get(r).matcher(cabecalho);
                if (!rotulo.find()) {
                    throw new HoleriteIlegivelException("bases");
                }
                var fim = Math.min(rotulo.end(), valores.length());
                lidos[r] = dinheiro(valores.substring(Math.min(inicio, fim), fim).replace("|", ""), "bases");
                inicio = rotulo.end();
            }
            return new BasesLidas(lidos[0], lidos[1], lidos[2], lidos[3], lidos[4]);
        }
        throw new HoleriteIlegivelException("bases");
    }

    private static Dinheiro dinheiro(String texto, String campo) {
        var valor = numero(texto, campo);
        return valor == null ? null : new Dinheiro(valor);
    }

    /** Número no formato brasileiro ({@code 1.234.567,89}); em branco é nulo. */
    private static BigDecimal numero(String texto, String campo) {
        var limpo = texto.strip();
        if (limpo.isEmpty()) {
            return null;
        }
        if (!NUMERO.matcher(limpo).matches()) {
            throw new HoleriteIlegivelException(campo);
        }
        return new BigDecimal(limpo.replace(".", "").replace(',', '.'));
    }
}
