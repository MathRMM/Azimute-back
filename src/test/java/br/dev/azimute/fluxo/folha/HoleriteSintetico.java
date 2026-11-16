package br.dev.azimute.fluxo.folha;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Gera, nos testes, um "Demonstrativo de Pagamento" no mesmo layout do holerite real: texto em largura fixa, com
 * colunas separadas por barras, totais, líquido e a linha de bases (SEC03). Empresa, nome, matrícula e valores são
 * fictícios; os valores são os do fluxo principal A do PRD. Nenhum PDF real entra no repositório.
 */
final class HoleriteSintetico {

    private static final String TRACOS = "|" + "-".repeat(98) + "|";
    private static final String SEPARADOR_DA_TABELA = "|------|" + "-".repeat(45) + "|" + "-".repeat(13) + "|"
            + "-".repeat(15) + "|" + "-".repeat(15) + "|";

    private String competencia = "Outubro 2026";
    private final List<String[]> rubricas = new ArrayList<>(List.of(
            new String[]{"0001", "Salário Mensal", "30,00", "5.000,00", ""},
            new String[]{"/314", "INSS", "14,00", "", "480,00"},
            new String[]{"/507", "Vale-Transporte", "6,00", "", "300,00"},
            new String[]{"2363", "Prev Priv Bas Func", "1,00", "", "50,00"},
            new String[]{"2364", "Prev Priv Vol Func", "5,00", "", "250,00"},
            new String[]{"/B02", "Decto Adto Salário", "", "", "2.500,00"}));
    private String totalVencimentos = "5.000,00";
    private String totalDescontos = "3.580,00";
    private String liquido = "1.420,00";
    private String[] bases = {"5.000,00", "5.000,00", "5.000,00", "400,00", "4.170,00"};
    private boolean comCabecalho = true;
    private boolean comLiquido = true;
    private int paginasExtras;
    private boolean cifrado;

    static HoleriteSintetico doExemplo() {
        return new HoleriteSintetico();
    }

    HoleriteSintetico naCompetencia(String porExtenso) {
        competencia = porExtenso;
        return this;
    }

    HoleriteSintetico comRubrica(String codigo, String descricao, String referencia, String vencimento,
                                 String desconto) {
        rubricas.add(new String[]{codigo, descricao, referencia, vencimento, desconto});
        return this;
    }

    HoleriteSintetico comTotais(String vencimentos, String descontos, String liquido) {
        this.totalVencimentos = vencimentos;
        this.totalDescontos = descontos;
        this.liquido = liquido;
        return this;
    }

    HoleriteSintetico semCabecalho() {
        comCabecalho = false;
        return this;
    }

    HoleriteSintetico semLiquido() {
        comLiquido = false;
        return this;
    }

    HoleriteSintetico semFgtsDoMes() {
        bases[3] = "";
        return this;
    }

    HoleriteSintetico comPaginasExtras(int paginas) {
        paginasExtras = paginas;
        return this;
    }

    HoleriteSintetico cifrado() {
        cifrado = true;
        return this;
    }

    List<String> linhas() {
        var linhas = new ArrayList<String>();
        linhas.add(TRACOS);
        linhas.add(comCabecalho
                ? "|" + centro("D E M O N S T R A T I V O   D E  P A G A M E N T O", 98) + "|"
                : "|" + centro("R E C I B O", 98) + "|");
        linhas.add(TRACOS);
        linhas.add("|" + esquerda("0001  EMPRESA FICTICIA S.A.", 98) + "|");
        linhas.add("|" + esquerda("      00.000.000/0001-00", 73) + esquerda(competencia, 25) + "|");
        linhas.add(TRACOS);
        linhas.add("|" + esquerda("Matrícula Nome do Funcionário", 53) + esquerda("Admissão     CBO      Cargo", 45)
                + "|");
        linhas.add("|" + esquerda("00012345  FULANO DE TAL", 53) + esquerda("01.02.2020                    0000", 45)
                + "|");
        linhas.add(TRACOS);
        linhas.add("|Código|" + esquerda("Descrição", 45) + "|" + esquerda("Referência", 13) + "|"
                + esquerda("Vencimentos", 15) + "|" + esquerda("Descontos", 15) + "|");
        linhas.add(TRACOS);
        for (var r : rubricas) {
            linhas.add("|" + direita(r[0], 6) + "|" + esquerda(r[1], 45) + "|" + direita(r[2], 13) + "|"
                    + direita(r[3], 15) + "|" + direita(r[4], 15) + "|");
        }
        while (linhas.size() < 27) {
            linhas.add("|      |" + " ".repeat(45) + "|" + " ".repeat(13) + "|" + " ".repeat(15) + "|"
                    + " ".repeat(15) + "|");
        }
        linhas.add(SEPARADOR_DA_TABELA);
        linhas.add("|" + " ".repeat(52) + "|" + esquerda("Totais", 13) + "|" + direita(totalVencimentos + " ", 15) + "|"
                + direita(totalDescontos + " ", 15) + "|");
        linhas.add(SEPARADOR_DA_TABELA);
        if (comLiquido) {
            linhas.add("|" + " ".repeat(52) + "|" + esquerda("Líquido             =======> ", 29) + "|"
                    + direita(liquido + " ", 15) + "|");
        }
        linhas.add("-".repeat(100));
        var cabecalho = "|Salário Base       Base INSS     Base FGTS       FGTS Mês      Base IRRF       Faixa IRRF         |";
        linhas.add(cabecalho);
        linhas.add(valoresDasBases(cabecalho));
        linhas.add("-".repeat(100));
        return linhas;
    }

    byte[] pdf() {
        try (var documento = new PDDocument(); var saida = new ByteArrayOutputStream()) {
            var fonte = new PDType1Font(Standard14Fonts.FontName.COURIER);
            var pagina = novaPagina(documento);
            try (var conteudo = new PDPageContentStream(documento, pagina)) {
                var y = pagina.getMediaBox().getHeight() - 20;
                for (var linha : linhas()) {
                    conteudo.beginText();
                    conteudo.setFont(fonte, 8);
                    conteudo.newLineAtOffset(10, y);
                    conteudo.showText(linha);
                    conteudo.endText();
                    y -= 12;
                }
            }
            for (int i = 0; i <= paginasExtras; i++) {
                var rodape = novaPagina(documento);
                try (var conteudo = new PDPageContentStream(documento, rodape)) {
                    conteudo.beginText();
                    conteudo.setFont(fonte, 8);
                    conteudo.newLineAtOffset(10, rodape.getMediaBox().getHeight() - 20);
                    conteudo.showText("#v.1 JUL/2019");
                    conteudo.endText();
                }
            }
            if (cifrado) {
                var politica = new StandardProtectionPolicy("dono", "", new AccessPermission());
                politica.setEncryptionKeyLength(128);
                documento.protect(politica);
            }
            documento.save(saida);
            return saida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Cada valor termina na mesma coluna em que termina o seu rótulo, como no layout real. */
    private String valoresDasBases(String cabecalho) {
        var linha = new StringBuilder(" ".repeat(cabecalho.length()));
        var rotulos = new String[]{"Salário Base", "Base INSS", "Base FGTS", "FGTS Mês", "Base IRRF"};
        for (int i = 0; i < rotulos.length; i++) {
            var fim = cabecalho.indexOf(rotulos[i]) + rotulos[i].length();
            var valor = bases[i];
            linha.replace(fim - valor.length(), fim, valor);
        }
        linha.setCharAt(0, '|');
        linha.setCharAt(linha.length() - 1, '|');
        return linha.toString();
    }

    private static PDPage novaPagina(PDDocument documento) {
        var pagina = new PDPage(PDRectangle.A4);
        documento.addPage(pagina);
        return pagina;
    }

    private static String esquerda(String texto, int largura) {
        return String.format("%-" + largura + "s", texto);
    }

    private static String direita(String texto, int largura) {
        return String.format("%" + largura + "s", texto);
    }

    private static String centro(String texto, int largura) {
        var antes = (largura - texto.length()) / 2;
        return esquerda(" ".repeat(antes) + texto, largura);
    }
}
