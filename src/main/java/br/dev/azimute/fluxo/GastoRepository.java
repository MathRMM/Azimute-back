package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.folha.HoleriteId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL sobre {@code fluxo.gastos}. As leituras de listagem ignoram os excluídos; só {@link #buscar} os enxerga, para o
 * reenvio e a restauração decidirem.
 */
@Repository
class GastoRepository {

    /** O gasto, o nome atual da categoria e se ele está excluído. */
    record Registro(Gasto gasto, String categoriaNome, boolean excluida) {}

    private static final String COLUNAS = """
            g.id, g.data, g.descricao, g.valor, g.categoria_id, g.forma, g.conta_id, g.holerite_id,
            c.nome AS categoria_nome, g.deleted_at IS NOT NULL AS excluida
            FROM fluxo.gastos g JOIN fluxo.categorias c ON c.id = g.categoria_id""";

    private final JdbcClient jdbc;

    GastoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Grava com o ID do cliente. {@code false} quando o ID já existe, vivo ou excluído; quem decide é o serviço.
     * {@code ON CONFLICT DO NOTHING} resolve a corrida entre dois envios simultâneos.
     */
    boolean inserirSeAusente(Gasto gasto) {
        return jdbc.sql("""
                        INSERT INTO fluxo.gastos (id, data, descricao, valor, categoria_id, forma, conta_id, holerite_id)
                        VALUES (:id, :data, :descricao, :valor, :categoria, :forma, :conta, :holerite)
                        ON CONFLICT DO NOTHING""")
                .param("id", gasto.id().valor()).param("data", gasto.data()).param("descricao", gasto.descricao())
                .param("valor", gasto.valor().valor()).param("categoria", gasto.categoria().valor())
                .param("forma", gasto.forma().codigo()).param("conta", conta(gasto.forma()))
                .param("holerite", holerite(gasto.forma()))
                .update() == 1;
    }

    Optional<Registro> buscar(GastoId id) {
        return jdbc.sql("SELECT " + COLUNAS + " WHERE g.id = :id")
                .param("id", id.valor())
                .query((rs, n) -> new Registro(mapear(rs), rs.getString("categoria_nome"), rs.getBoolean("excluida")))
                .optional();
    }

    /** Altera o gasto vivo no lugar; a forma e o holerite não mudam. */
    void alterar(Gasto gasto) {
        jdbc.sql("""
                        UPDATE fluxo.gastos
                        SET data = :data, descricao = :descricao, valor = :valor, categoria_id = :categoria,
                            conta_id = :conta, updated_at = now()
                        WHERE id = :id AND deleted_at IS NULL""")
                .param("id", gasto.id().valor()).param("data", gasto.data()).param("descricao", gasto.descricao())
                .param("valor", gasto.valor().valor()).param("categoria", gasto.categoria().valor())
                .param("conta", conta(gasto.forma()))
                .update();
    }

    void excluir(GastoId id) {
        jdbc.sql("UPDATE fluxo.gastos SET deleted_at = now(), updated_at = now() WHERE id = :id AND deleted_at IS NULL")
                .param("id", id.valor()).update();
    }

    void restaurar(GastoId id) {
        jdbc.sql("UPDATE fluxo.gastos SET deleted_at = NULL, updated_at = now() WHERE id = :id")
                .param("id", id.valor()).update();
    }

    /** Gastos vivos da competência pela {@code data} (RN09), do mais recente ao mais antigo (RF13). */
    List<GastoComCategoria> listar(Competencia competencia, CategoriaId categoria) {
        var inicio = competencia.mes().atDay(1);
        return jdbc.sql("SELECT " + COLUNAS + """
                         WHERE g.deleted_at IS NULL AND g.data >= :inicio AND g.data < :fim
                          AND (CAST(:categoria AS UUID) IS NULL OR g.categoria_id = :categoria)
                        ORDER BY g.data DESC, g.created_at DESC""")
                .param("inicio", inicio).param("fim", inicio.plusMonths(1))
                .param("categoria", categoria == null ? null : categoria.valor())
                .query((rs, n) -> new GastoComCategoria(mapear(rs), rs.getString("categoria_nome")))
                .list();
    }

    private static UUID conta(FormaPagamento forma) {
        return switch (forma) {
            case FormaPagamento.AVista a -> a.conta().valor();
            case FormaPagamento.DescontoEmFolha d -> null;
        };
    }

    private static UUID holerite(FormaPagamento forma) {
        return switch (forma) {
            case FormaPagamento.AVista a -> null;
            case FormaPagamento.DescontoEmFolha d -> d.holerite().valor();
        };
    }

    private static Gasto mapear(ResultSet rs) throws SQLException {
        FormaPagamento forma = "A_VISTA".equals(rs.getString("forma"))
                ? new FormaPagamento.AVista(new ContaId(rs.getObject("conta_id", UUID.class)))
                : new FormaPagamento.DescontoEmFolha(new HoleriteId(rs.getObject("holerite_id", UUID.class)));
        return new Gasto(new GastoId(rs.getObject("id", UUID.class)), rs.getObject("data", LocalDate.class),
                rs.getString("descricao"), new Dinheiro(rs.getBigDecimal("valor")),
                new CategoriaId(rs.getObject("categoria_id", UUID.class)), forma);
    }
}
