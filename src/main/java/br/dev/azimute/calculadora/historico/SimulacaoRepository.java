package br.dev.azimute.calculadora.historico;

import br.dev.azimute.comum.Dinheiro;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL explícito sobre {@code calculadora.simulacoes}. O ID vem do cliente, por isso a inserção usa
 * {@code ON CONFLICT DO NOTHING}: quem decide o que fazer com um ID repetido é o serviço (decisão D2).
 */
@Repository
class SimulacaoRepository {

    private static final String COLUNAS = """
            id, bem, data, local, instituicao, valor_bem, entrada, parcela, prazo, valor_financiado,
            taxa_mensal, taxa_anual, total_pago, total_juros, observacao, origem, ancora_id, status,
            calculada_em, sincronizada_em""";

    private final JdbcClient jdbc;

    SimulacaoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<SimulacaoSalva> buscar(SimulacaoId id) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM calculadora.simulacoes WHERE id = :id")
                .param("id", id.valor())
                .query(SimulacaoRepository::mapear)
                .optional();
    }

    /** @return {@code false} se o ID já existia (nada foi gravado) */
    boolean inserirSeAusente(SimulacaoId id, DadosSimulacao d) {
        var linhas = jdbc.sql("""
                        INSERT INTO calculadora.simulacoes
                            (id, bem, data, local, instituicao, valor_bem, entrada, parcela, prazo, valor_financiado,
                             taxa_mensal, taxa_anual, total_pago, total_juros, observacao, origem, ancora_id,
                             calculada_em)
                        VALUES
                            (:id, :bem, :data, :local, :instituicao, :valorBem, :entrada, :parcela, :prazo,
                             :valorFinanciado, :taxaMensal, :taxaAnual, :totalPago, :totalJuros, :observacao,
                             :origem, :ancoraId, :calculadaEm)
                        ON CONFLICT (id) DO NOTHING""")
                .param("id", id.valor())
                .param("bem", d.bem())
                .param("data", d.data())
                .param("local", d.local())
                .param("instituicao", d.instituicao())
                .param("valorBem", d.valorBem().valor())
                .param("entrada", d.entrada().valor())
                .param("parcela", d.parcela().valor())
                .param("prazo", d.prazo())
                .param("valorFinanciado", d.valorFinanciado().valor())
                .param("taxaMensal", d.taxaMensal())
                .param("taxaAnual", d.taxaAnual())
                .param("totalPago", d.totalPago().valor())
                .param("totalJuros", d.totalJuros().valor())
                .param("observacao", d.observacao())
                .param("origem", d.origem().name())
                .param("ancoraId", d.ancoraId() == null ? null : d.ancoraId().valor())
                .param("calculadaEm", OffsetDateTime.ofInstant(d.calculadaEm(), ZoneOffset.UTC))
                .update();
        return linhas == 1;
    }

    /** Altera só os campos anotativos. O trigger do banco barra qualquer outro (RN09). */
    void anotar(SimulacaoId id, Anotacao anotacao) {
        var sets = new java.util.ArrayList<String>();
        if (anotacao.alteraObservacao()) sets.add("observacao = :observacao");
        if (anotacao.status() != null) sets.add("status = :status");
        if (sets.isEmpty()) return;

        var comando = jdbc.sql("UPDATE calculadora.simulacoes SET " + String.join(", ", sets) + " WHERE id = :id")
                .param("id", id.valor());
        if (anotacao.alteraObservacao()) comando = comando.param("observacao", anotacao.observacao());
        if (anotacao.status() != null) comando = comando.param("status", anotacao.status().name());
        comando.update();
    }

    List<String> estimativasAncoradasEm(SimulacaoId id) {
        return jdbc.sql("SELECT id FROM calculadora.simulacoes WHERE ancora_id = :id ORDER BY calculada_em")
                .param("id", id.valor())
                .query((rs, linha) -> rs.getObject("id", UUID.class).toString())
                .list();
    }

    void excluir(SimulacaoId id) {
        jdbc.sql("DELETE FROM calculadora.simulacoes WHERE id = :id").param("id", id.valor()).update();
    }

    /** Ordem: data mais recente primeiro. Filtro por bem ignora maiúsculas e espaços nas pontas. */
    List<SimulacaoSalva> listar(String bem) {
        var sql = "SELECT " + COLUNAS + " FROM calculadora.simulacoes"
                + (bem == null ? "" : " WHERE lower(btrim(bem)) = lower(btrim(:bem))")
                + " ORDER BY data DESC, calculada_em DESC, id";
        var consulta = jdbc.sql(sql);
        if (bem != null) {
            consulta = consulta.param("bem", bem);
        }
        return consulta.query(SimulacaoRepository::mapear).list();
    }

    private static SimulacaoSalva mapear(ResultSet rs, int linha) throws SQLException {
        var ancora = rs.getObject("ancora_id", UUID.class);
        var dados = new DadosSimulacao(
                rs.getString("bem"),
                rs.getObject("data", java.time.LocalDate.class),
                rs.getString("local"),
                rs.getString("instituicao"),
                new Dinheiro(rs.getBigDecimal("valor_bem")),
                new Dinheiro(rs.getBigDecimal("entrada")),
                new Dinheiro(rs.getBigDecimal("parcela")),
                rs.getInt("prazo"),
                new Dinheiro(rs.getBigDecimal("valor_financiado")),
                rs.getBigDecimal("taxa_mensal"),
                rs.getBigDecimal("taxa_anual"),
                new Dinheiro(rs.getBigDecimal("total_pago")),
                new Dinheiro(rs.getBigDecimal("total_juros")),
                OrigemSimulacao.valueOf(rs.getString("origem")),
                ancora == null ? null : new SimulacaoId(ancora),
                rs.getString("observacao"),
                instante(rs.getTimestamp("calculada_em")));
        return new SimulacaoSalva(
                new SimulacaoId(rs.getObject("id", UUID.class)),
                dados,
                StatusSimulacao.valueOf(rs.getString("status")),
                instante(rs.getTimestamp("sincronizada_em")));
    }

    private static java.time.Instant instante(Timestamp timestamp) {
        return timestamp.toInstant();
    }
}
