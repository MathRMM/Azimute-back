package br.dev.azimute.fluxo.tabelas;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.fluxo.folha.HoleriteId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.UUID;

/** Escrita em {@code fluxo.ganhos}, usada pela API de ganhos e pelo holerite. */
@Repository
public class TabelaDeGanhos {

    /** Um ganho a gravar. O holerite só existe no ganho {@code SALARIO}. */
    public record Linha(UUID id, Competencia competencia, String descricao, String tipo, Dinheiro bruto,
                        Dinheiro liquido, HoleriteId holerite) {

        public Linha {
            Objects.requireNonNull(id, "id");
        }
    }

    private static final String INSERIR = """
            INSERT INTO fluxo.ganhos (id, competencia, descricao, tipo, bruto, liquido, holerite_id)
            VALUES (:id, :competencia, :descricao, :tipo, :bruto, :liquido, :holerite)
            """;

    private final JdbcClient jdbc;

    TabelaDeGanhos(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code false} quando o ID já existe, vivo ou excluído; quem decide o que fazer é quem chamou. */
    public boolean inserirSeAusente(Linha g) {
        return parametros(jdbc.sql(INSERIR + "ON CONFLICT DO NOTHING"), g).update() == 1;
    }

    /**
     * Grava ou, se o ID já existe, atualiza bruto e líquido e traz de volta da exclusão. É o caso do ganho gerado pelo
     * holerite, que tem referência fixa e é reaplicado (T6).
     */
    public void inserirOuReviver(Linha g) {
        parametros(jdbc.sql(INSERIR + """
                ON CONFLICT (id) DO UPDATE
                SET bruto = EXCLUDED.bruto, liquido = EXCLUDED.liquido, deleted_at = NULL, updated_at = now()"""), g)
                .update();
    }

    /** Exclusão lógica do ganho vivo, sem os recebimentos: cada chamador decide o que fazer com eles. */
    public void excluir(UUID id) {
        jdbc.sql("UPDATE fluxo.ganhos SET deleted_at = now(), updated_at = now() WHERE id = :id AND deleted_at IS NULL")
                .param("id", id).update();
    }

    private static JdbcClient.StatementSpec parametros(JdbcClient.StatementSpec sql, Linha g) {
        return sql.param("id", g.id()).param("competencia", g.competencia().mes().atDay(1))
                .param("descricao", g.descricao()).param("tipo", g.tipo())
                .param("bruto", g.bruto().valor()).param("liquido", g.liquido().valor())
                .param("holerite", g.holerite() == null ? null : g.holerite().valor());
    }
}
