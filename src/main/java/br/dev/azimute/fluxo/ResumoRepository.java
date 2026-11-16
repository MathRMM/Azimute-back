package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Leituras do resumo do mês (RF50, T14), sempre só sobre os registros vivos. */
@Repository
class ResumoRepository {

    /** Um gasto da competência, ou o total de uma categoria num período. */
    record GastoDoMes(CategoriaId categoria, String nome, Dinheiro valor) {}

    private final JdbcClient jdbc;

    ResumoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Gastos à vista e em folha cuja data cai na competência (RN09). */
    List<GastoDoMes> gastos(Competencia competencia) {
        var inicio = competencia.primeiroDia();
        return jdbc.sql("""
                        SELECT g.categoria_id, c.nome, g.valor
                        FROM fluxo.gastos g JOIN fluxo.categorias c ON c.id = g.categoria_id
                        WHERE g.deleted_at IS NULL AND g.data >= :inicio AND g.data < :fim""")
                .param("inicio", inicio).param("fim", inicio.plusMonths(1))
                .query(ResumoRepository::mapear).list();
    }

    /** Total de cada categoria nas 3 competências anteriores, juntas (RN17). */
    List<GastoDoMes> totaisDosTresMesesAnteriores(Competencia competencia) {
        var inicio = competencia.primeiroDia();
        return jdbc.sql("""
                        SELECT g.categoria_id, c.nome, sum(g.valor) AS valor
                        FROM fluxo.gastos g JOIN fluxo.categorias c ON c.id = g.categoria_id
                        WHERE g.deleted_at IS NULL AND g.data >= :desde AND g.data < :inicio
                        GROUP BY g.categoria_id, c.nome""")
                .param("desde", inicio.minusMonths(3)).param("inicio", inicio)
                .query(ResumoRepository::mapear).list();
    }

    /** Líquido dos ganhos da competência (T14); no salário, adiantamento + líquido do holerite (RN14). */
    Dinheiro recebido(Competencia competencia) {
        return new Dinheiro(jdbc.sql("""
                        SELECT coalesce(sum(liquido), 0) FROM fluxo.ganhos
                        WHERE competencia = :c AND deleted_at IS NULL""")
                .param("c", competencia.primeiroDia()).query(java.math.BigDecimal.class).single());
    }

    private static GastoDoMes mapear(ResultSet rs, int linha) throws SQLException {
        return new GastoDoMes(new CategoriaId(rs.getObject("categoria_id", UUID.class)), rs.getString("nome"),
                new Dinheiro(rs.getBigDecimal("valor")));
    }
}
