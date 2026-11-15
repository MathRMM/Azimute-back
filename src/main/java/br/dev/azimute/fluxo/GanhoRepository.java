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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL sobre {@code fluxo.ganhos} e {@code fluxo.recebimentos}. O ganho e os recebimentos dele são excluídos e
 * restaurados juntos. As listagens ignoram os excluídos; só {@link #buscar} os enxerga.
 */
@Repository
class GanhoRepository {

    /** O ganho, os recebimentos dele e se ele está excluído. */
    record Registro(Ganho ganho, List<Recebimento> recebimentos, boolean excluido) {}

    private static final String COLUNAS_GANHO =
            "id, competencia, descricao, tipo, bruto, liquido, holerite_id, deleted_at IS NOT NULL AS excluido";
    private static final String COLUNAS_RECEBIMENTO =
            "r.id, r.ganho_id, r.competencia, r.papel, r.valor, r.data, r.conta_id, r.lancado_antes";

    private final JdbcClient jdbc;

    GanhoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code false} quando o ID já existe, vivo ou excluído; quem decide é o serviço. */
    boolean inserirSeAusente(Ganho ganho) {
        return jdbc.sql("""
                        INSERT INTO fluxo.ganhos (id, competencia, descricao, tipo, bruto, liquido, holerite_id)
                        VALUES (:id, :competencia, :descricao, :tipo, :bruto, :liquido, :holerite)
                        ON CONFLICT DO NOTHING""")
                .param("id", ganho.id().valor()).param("competencia", dia1(ganho.competencia()))
                .param("descricao", ganho.descricao()).param("tipo", ganho.tipo().name())
                .param("bruto", ganho.bruto().valor()).param("liquido", ganho.liquido().valor())
                .param("holerite", ganho.holerite() == null ? null : ganho.holerite().valor())
                .update() == 1;
    }

    void inserirRecebimento(Recebimento r) {
        jdbc.sql("""
                        INSERT INTO fluxo.recebimentos (id, ganho_id, competencia, papel, valor, data, conta_id,
                                                        lancado_antes)
                        VALUES (:id, :ganho, :competencia, :papel, :valor, :data, :conta, :antes)""")
                .param("id", r.id()).param("ganho", r.ganho() == null ? null : r.ganho().valor())
                .param("competencia", dia1(r.competencia())).param("papel", r.papel().name())
                .param("valor", r.valor().valor()).param("data", r.data()).param("conta", r.conta().valor())
                .param("antes", r.lancadoAntes())
                .update();
    }

    Optional<Registro> buscar(GanhoId id) {
        return jdbc.sql("SELECT " + COLUNAS_GANHO + " FROM fluxo.ganhos WHERE id = :id")
                .param("id", id.valor())
                .query((rs, n) -> new Registro(mapearGanho(rs), List.of(), rs.getBoolean("excluido")))
                .optional()
                .map(r -> new Registro(r.ganho(), recebimentosDe(id), r.excluido()));
    }

    void alterar(Ganho ganho) {
        jdbc.sql("""
                        UPDATE fluxo.ganhos
                        SET competencia = :competencia, descricao = :descricao, tipo = :tipo, bruto = :bruto,
                            liquido = :liquido, updated_at = now()
                        WHERE id = :id AND deleted_at IS NULL""")
                .param("id", ganho.id().valor()).param("competencia", dia1(ganho.competencia()))
                .param("descricao", ganho.descricao()).param("tipo", ganho.tipo().name())
                .param("bruto", ganho.bruto().valor()).param("liquido", ganho.liquido().valor())
                .update();
    }

    void alterarRecebimento(Recebimento r) {
        jdbc.sql("""
                        UPDATE fluxo.recebimentos
                        SET competencia = :competencia, valor = :valor, data = :data, conta_id = :conta,
                            updated_at = now()
                        WHERE id = :id AND deleted_at IS NULL""")
                .param("id", r.id()).param("competencia", dia1(r.competencia())).param("valor", r.valor().valor())
                .param("data", r.data()).param("conta", r.conta().valor())
                .update();
    }

    void excluir(GanhoId id) {
        jdbc.sql("""
                        UPDATE fluxo.recebimentos SET deleted_at = now(), updated_at = now()
                        WHERE ganho_id = :id AND deleted_at IS NULL""")
                .param("id", id.valor()).update();
        jdbc.sql("UPDATE fluxo.ganhos SET deleted_at = now(), updated_at = now() WHERE id = :id AND deleted_at IS NULL")
                .param("id", id.valor()).update();
    }

    void restaurar(GanhoId id) {
        jdbc.sql("UPDATE fluxo.ganhos SET deleted_at = NULL, updated_at = now() WHERE id = :id")
                .param("id", id.valor()).update();
        jdbc.sql("UPDATE fluxo.recebimentos SET deleted_at = NULL, updated_at = now() WHERE ganho_id = :id")
                .param("id", id.valor()).update();
    }

    /** Ganhos vivos da competência informada (RN09), com os recebimentos vivos de cada um (RF23, RF24). */
    List<GanhoComRecebimentos> listar(Competencia competencia) {
        var ganhos = jdbc.sql("SELECT " + COLUNAS_GANHO + """
                         FROM fluxo.ganhos
                        WHERE competencia = :competencia AND deleted_at IS NULL
                        ORDER BY created_at, id""")
                .param("competencia", dia1(competencia))
                .query((rs, n) -> mapearGanho(rs))
                .list();
        var porGanho = new LinkedHashMap<GanhoId, List<Recebimento>>();
        ganhos.forEach(g -> porGanho.put(g.id(), new ArrayList<>()));
        jdbc.sql("SELECT " + COLUNAS_RECEBIMENTO + """
                         FROM fluxo.recebimentos r JOIN fluxo.ganhos g ON g.id = r.ganho_id
                        WHERE g.competencia = :competencia AND g.deleted_at IS NULL AND r.deleted_at IS NULL
                        ORDER BY r.data, r.papel""")
                .param("competencia", dia1(competencia))
                .query((rs, n) -> mapearRecebimento(rs))
                .list()
                .forEach(r -> porGanho.get(r.ganho()).add(r));
        return ganhos.stream().map(g -> new GanhoComRecebimentos(g, porGanho.get(g.id()))).toList();
    }

    private List<Recebimento> recebimentosDe(GanhoId id) {
        return jdbc.sql("SELECT " + COLUNAS_RECEBIMENTO + " FROM fluxo.recebimentos r WHERE r.ganho_id = :id "
                        + "ORDER BY r.data, r.papel")
                .param("id", id.valor())
                .query((rs, n) -> mapearRecebimento(rs))
                .list();
    }

    private static LocalDate dia1(Competencia competencia) {
        return competencia.mes().atDay(1);
    }

    private static Competencia competencia(ResultSet rs, String coluna) throws SQLException {
        return new Competencia(YearMonth.from(rs.getObject(coluna, LocalDate.class)));
    }

    private static Ganho mapearGanho(ResultSet rs) throws SQLException {
        var holerite = rs.getObject("holerite_id", UUID.class);
        return new Ganho(new GanhoId(rs.getObject("id", UUID.class)), competencia(rs, "competencia"),
                rs.getString("descricao"), TipoGanho.valueOf(rs.getString("tipo")),
                new Dinheiro(rs.getBigDecimal("bruto")), new Dinheiro(rs.getBigDecimal("liquido")),
                holerite == null ? null : new HoleriteId(holerite));
    }

    private static Recebimento mapearRecebimento(ResultSet rs) throws SQLException {
        var ganho = rs.getObject("ganho_id", UUID.class);
        return new Recebimento(rs.getObject("id", UUID.class), ganho == null ? null : new GanhoId(ganho),
                competencia(rs, "competencia"), Recebimento.Papel.valueOf(rs.getString("papel")),
                new Dinheiro(rs.getBigDecimal("valor")), rs.getObject("data", LocalDate.class),
                new ContaId(rs.getObject("conta_id", UUID.class)), rs.getBoolean("lancado_antes"));
    }
}
