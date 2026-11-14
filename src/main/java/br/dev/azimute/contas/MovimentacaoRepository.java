package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/** SQL sobre {@code contas.movimentacoes}. Toda leitura ignora as excluídas ({@code deleted_at}, RN14). */
@Repository
class MovimentacaoRepository {

    /** Soma com sinal (crédito positivo, débito negativo) e quantidade de movimentações. */
    record Resumo(Dinheiro efeito, int quantidade) {}

    private final JdbcClient jdbc;

    MovimentacaoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Movimentações vivas da conta com data <b>posterior</b> a {@code data}: o saldo real de uma conciliação vale
     * para o fim do dia dela (P2). Uma soma no banco, sem percorrer o histórico na aplicação (RNF03).
     */
    Resumo resumoDesde(ContaId conta, LocalDate data) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN conta_destino = :conta THEN valor ELSE -valor END), 0) AS efeito,
                               COUNT(*) AS quantidade
                        FROM contas.movimentacoes
                        WHERE (conta_origem = :conta OR conta_destino = :conta)
                          AND data > :data AND deleted_at IS NULL""")
                .param("conta", conta.valor()).param("data", data)
                .query((rs, n) -> new Resumo(new Dinheiro(rs.getBigDecimal("efeito")), rs.getInt("quantidade")))
                .single();
    }
}
