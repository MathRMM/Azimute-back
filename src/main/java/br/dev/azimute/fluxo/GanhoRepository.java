package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.folha.HoleriteId;
import br.dev.azimute.fluxo.tabelas.PapelDeRecebimento;
import br.dev.azimute.fluxo.tabelas.RecebimentoId;
import br.dev.azimute.fluxo.tabelas.TabelaDeGanhos;
import br.dev.azimute.fluxo.tabelas.TabelaDeRecebimentos;
import br.dev.azimute.fluxo.tabelas.TipoGanho;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
    private final TabelaDeGanhos ganhos;
    private final TabelaDeRecebimentos recebimentos;

    GanhoRepository(JdbcClient jdbc, TabelaDeGanhos ganhos, TabelaDeRecebimentos recebimentos) {
        this.jdbc = jdbc;
        this.ganhos = ganhos;
        this.recebimentos = recebimentos;
    }

    /** {@code false} quando o ID já existe, vivo ou excluído; quem decide é o serviço. */
    boolean inserirSeAusente(Ganho ganho) {
        return ganhos.inserirSeAusente(new TabelaDeGanhos.Linha(ganho.id(), ganho.competencia(),
                ganho.descricao(), ganho.tipo(), ganho.bruto(), ganho.liquido(), ganho.holerite()));
    }

    void inserirRecebimento(Recebimento r) {
        recebimentos.inserir(new TabelaDeRecebimentos.Linha(r.id(), r.ganho(),
                r.competencia(), r.papel(), r.valor(), r.data(), r.conta(), r.lancadoAntes()));
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
                .param("id", ganho.id().valor()).param("competencia", ganho.competencia().primeiroDia())
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
                .param("id", r.id().valor()).param("competencia", r.competencia().primeiroDia()).param("valor", r.valor().valor())
                .param("data", r.data()).param("conta", r.conta().valor())
                .update();
    }

    void excluir(GanhoId id) {
        jdbc.sql("""
                        UPDATE fluxo.recebimentos SET deleted_at = now(), updated_at = now()
                        WHERE ganho_id = :id AND deleted_at IS NULL""")
                .param("id", id.valor()).update();
        ganhos.excluir(id);
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
                .param("competencia", competencia.primeiroDia())
                .query((rs, n) -> mapearGanho(rs))
                .list();
        var porGanho = new LinkedHashMap<GanhoId, List<Recebimento>>();
        ganhos.forEach(g -> porGanho.put(g.id(), new ArrayList<>()));
        jdbc.sql("SELECT " + COLUNAS_RECEBIMENTO + """
                         FROM fluxo.recebimentos r JOIN fluxo.ganhos g ON g.id = r.ganho_id
                        WHERE g.competencia = :competencia AND g.deleted_at IS NULL AND r.deleted_at IS NULL
                        ORDER BY r.data, r.papel""")
                .param("competencia", competencia.primeiroDia())
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

    private static Competencia competencia(ResultSet rs, String coluna) throws SQLException {
        return Competencia.doDia(rs.getObject(coluna, LocalDate.class));
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
        return new Recebimento(new RecebimentoId(rs.getObject("id", UUID.class)), ganho == null ? null : new GanhoId(ganho),
                competencia(rs, "competencia"), PapelDeRecebimento.valueOf(rs.getString("papel")),
                new Dinheiro(rs.getBigDecimal("valor")), rs.getObject("data", LocalDate.class),
                new ContaId(rs.getObject("conta_id", UUID.class)), rs.getBoolean("lancado_antes"));
    }
}
