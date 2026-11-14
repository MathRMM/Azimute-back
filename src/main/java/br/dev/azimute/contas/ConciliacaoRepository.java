package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** SQL sobre {@code contas.conciliacoes}. Só inserção e leitura: o banco recusa alteração e exclusão (RN07). */
@Repository
class ConciliacaoRepository {

    private static final String COLUNAS =
            "id, conta_id, data, saldo_real, saldo_calculado, divergencia, justificativa";

    private final JdbcClient jdbc;

    ConciliacaoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    ConciliacaoId inserir(ContaId conta, LocalDate data, Dinheiro saldoReal, Dinheiro saldoCalculado,
                          Dinheiro divergencia, String justificativa) {
        var id = jdbc.sql("""
                        INSERT INTO contas.conciliacoes
                            (conta_id, data, saldo_real, saldo_calculado, divergencia, justificativa)
                        VALUES (:conta, :data, :real, :calculado, :divergencia, :justificativa) RETURNING id""")
                .param("conta", conta.valor()).param("data", data)
                .param("real", saldoReal.valor()).param("calculado", saldoCalculado.valor())
                .param("divergencia", divergencia.valor()).param("justificativa", justificativa)
                .query(UUID.class).single();
        return new ConciliacaoId(id);
    }

    /** A base do saldo atual (RN06). */
    Optional<Conciliacao> ultima(ContaId conta) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM contas.conciliacoes WHERE conta_id = :conta "
                        + "ORDER BY data DESC LIMIT 1")
                .param("conta", conta.valor())
                .query(ConciliacaoRepository::mapear)
                .optional();
    }

    private static Conciliacao mapear(ResultSet rs, int linha) throws SQLException {
        return new Conciliacao(new ConciliacaoId(rs.getObject("id", UUID.class)),
                new ContaId(rs.getObject("conta_id", UUID.class)), rs.getObject("data", LocalDate.class),
                new Dinheiro(rs.getBigDecimal("saldo_real")), new Dinheiro(rs.getBigDecimal("saldo_calculado")),
                new Dinheiro(rs.getBigDecimal("divergencia")), rs.getString("justificativa"));
    }
}
