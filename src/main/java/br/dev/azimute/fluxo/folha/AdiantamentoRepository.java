package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.tabelas.TabelaDeRecebimentos;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** SQL sobre os adiantamentos lançados antes do holerite (RF34): recebimentos com {@code lancado_antes}. */
@Repository
class AdiantamentoRepository {

    /** Um recebimento pelo ID, de qualquer papel, para o reenvio decidir. */
    record Adiantamento(UUID id, Competencia competencia, String papel, Dinheiro valor, LocalDate data, ContaId conta,
                        boolean lancadoAntes) {}

    private static final String COLUNAS = "id, competencia, papel, valor, data, conta_id, lancado_antes";

    private final JdbcClient jdbc;
    private final TabelaDeRecebimentos recebimentos;

    AdiantamentoRepository(JdbcClient jdbc, TabelaDeRecebimentos recebimentos) {
        this.jdbc = jdbc;
        this.recebimentos = recebimentos;
    }

    Optional<Adiantamento> buscar(UUID id) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM fluxo.recebimentos WHERE id = :id")
                .param("id", id).query(AdiantamentoRepository::mapear).optional();
    }

    /** O adiantamento antecipado vivo da competência, solto ou já associado a este ganho. */
    Optional<Adiantamento> antecipado(Competencia competencia, UUID ganho) {
        return jdbc.sql("SELECT " + COLUNAS + """
                         FROM fluxo.recebimentos
                        WHERE papel = 'ADIANTAMENTO' AND lancado_antes AND competencia = :c AND deleted_at IS NULL
                          AND (ganho_id IS NULL OR ganho_id = :ganho)
                        ORDER BY created_at LIMIT 1""")
                .param("c", competencia.primeiroDia()).param("ganho", ganho)
                .query(AdiantamentoRepository::mapear).optional();
    }

    boolean existeAntecipado(Competencia competencia) {
        return jdbc.sql("""
                        SELECT count(*) FROM fluxo.recebimentos
                        WHERE papel = 'ADIANTAMENTO' AND lancado_antes AND competencia = :c AND deleted_at IS NULL""")
                .param("c", competencia.primeiroDia()).query(Long.class).single() > 0;
    }

    /** Solto, sem ganho: o holerite da competência o associa depois (RF34). */
    void inserir(Adiantamento a) {
        recebimentos.inserir(new TabelaDeRecebimentos.Linha(a.id(), null, a.competencia(), "ADIANTAMENTO",
                a.valor(), a.data(), a.conta(), true));
    }

    private static Adiantamento mapear(ResultSet rs, int n) throws SQLException {
        return new Adiantamento(rs.getObject("id", UUID.class),
                Competencia.doDia(rs.getObject("competencia", LocalDate.class)), rs.getString("papel"),
                new Dinheiro(rs.getBigDecimal("valor")), rs.getObject("data", LocalDate.class),
                new ContaId(rs.getObject("conta_id", UUID.class)), rs.getBoolean("lancado_antes"));
    }
}
