package br.dev.azimute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lê das migrations a lista de valores de um {@code CHECK (coluna IN (...))}, para os testes conferirem que cada
 * enum tem exatamente os nomes que o banco aceita.
 */
public final class ChecksDoBanco {

    private ChecksDoBanco() {
    }

    /** @param migration nome do arquivo em {@code db/migration}; @param tabela com o schema, como no DDL */
    public static List<String> valores(String migration, String tabela, String coluna) {
        var sql = ler(migration);
        var inicio = sql.indexOf("CREATE TABLE " + tabela + " (");
        if (inicio < 0) {
            throw new IllegalArgumentException("Tabela " + tabela + " não está em " + migration);
        }
        var ddl = sql.substring(inicio, sql.indexOf(";", inicio));
        var check = Pattern.compile("CHECK \\(" + coluna + " IN \\(([^)]*)\\)\\)").matcher(ddl);
        if (!check.find()) {
            throw new IllegalArgumentException("Sem CHECK de " + coluna + " em " + tabela);
        }
        return Arrays.stream(check.group(1).split(",")).map(v -> v.strip().replace("'", "")).toList();
    }

    private static String ler(String migration) {
        try (var entrada = ChecksDoBanco.class.getResourceAsStream("/db/migration/" + migration)) {
            if (entrada == null) {
                throw new IllegalArgumentException("Migration não encontrada: " + migration);
            }
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
