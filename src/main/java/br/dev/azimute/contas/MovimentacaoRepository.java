package br.dev.azimute.contas;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL sobre {@code contas.movimentacoes}. Toda leitura de saldo e de extrato ignora as excluídas
 * ({@code deleted_at}, RN14); só {@link #buscar} as enxerga, para o reenvio e a restauração decidirem.
 */
@Repository
class MovimentacaoRepository {

    /** Soma com sinal (crédito positivo, débito negativo) e quantidade de movimentações. */
    record Resumo(Dinheiro efeito, int quantidade) {}

    /** A movimentação e se ela está excluída (exclusão lógica, RF13). */
    record Registro(Movimentacao movimentacao, boolean excluida) {}

    private static final String COLUNAS =
            "id, tipo, conta_origem, conta_destino, valor, data, ganho_id, gasto_id, cartao_id, fatura_comp, motivo";

    private final JdbcClient jdbc;

    MovimentacaoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Movimentações vivas da conta com data <b>posterior</b> a {@code data}: o saldo real de uma conciliação vale
     * para o fim do dia dela (P2). Uma soma no banco, sem percorrer o histórico na aplicação (RNF03).
     */
    Resumo resumoDesde(ContaId conta, LocalDate data) {
        return resumo(conta, data, SEM_LIMITE);
    }

    /** Maior data que o {@code DATE} do Postgres aceita com folga; {@code LocalDate.MAX} estoura o tipo. */
    private static final LocalDate SEM_LIMITE = LocalDate.of(9999, 12, 31);

    /** Como {@link #resumoDesde}, mas só até {@code ate}, inclusive: é o que o saldo numa data precisa. */
    Resumo resumoEntre(ContaId conta, LocalDate depoisDe, LocalDate ate) {
        return resumo(conta, depoisDe, ate);
    }

    /**
     * Grava com o ID do cliente. {@code false} quando o ID já existe (viva ou excluída) ou, na fachada, quando a
     * mesma referência de ganho, gasto ou fatura já está viva (T5): quem decide o que fazer é o serviço (P1).
     * {@code ON CONFLICT DO NOTHING} sem alvo cobre a chave primária e os índices únicos parciais, e resolve a
     * corrida entre dois envios simultâneos.
     */
    boolean inserirSeAusente(Movimentacao m) {
        var campos = Campos.de(m);
        return jdbc.sql("""
                        INSERT INTO contas.movimentacoes (id, tipo, conta_origem, conta_destino, valor, data,
                                                          ganho_id, gasto_id, cartao_id, fatura_comp, motivo)
                        VALUES (:id, :tipo, :origem, :destino, :valor, :data, :ganho, :gasto, :cartao, :fatura, :motivo)
                        ON CONFLICT DO NOTHING""")
                .param("id", m.id().valor()).param("tipo", m.tipo().name())
                .param("origem", campos.origem()).param("destino", campos.destino())
                .param("valor", m.valor().valor()).param("data", m.data())
                .param("ganho", campos.ganho()).param("gasto", campos.gasto()).param("cartao", campos.cartao())
                .param("fatura", campos.fatura()).param("motivo", campos.motivo())
                .update() == 1;
    }

    Optional<Registro> buscar(MovimentacaoId id) {
        return jdbc.sql("SELECT " + COLUNAS + ", deleted_at IS NOT NULL AS excluida "
                        + "FROM contas.movimentacoes WHERE id = :id")
                .param("id", id.valor())
                .query((rs, n) -> new Registro(mapear(rs), rs.getBoolean("excluida")))
                .optional();
    }

    /**
     * A entrada ou saída lançada por outro módulo com essa referência de ganho ou gasto (RF42 do PRD do fluxo).
     * A viva vem primeiro; sem viva, a excluída mais recente, que é a que um restaurar traria de volta.
     */
    Optional<Registro> buscarPorReferencia(UUID referencia) {
        return jdbc.sql("SELECT " + COLUNAS + ", deleted_at IS NOT NULL AS excluida FROM contas.movimentacoes "
                        + "WHERE tipo IN ('ENTRADA', 'SAIDA') AND (ganho_id = :ref OR gasto_id = :ref) "
                        + "ORDER BY deleted_at IS NULL DESC, deleted_at DESC LIMIT 1")
                .param("ref", referencia)
                .query((rs, n) -> new Registro(mapear(rs), rs.getBoolean("excluida")))
                .optional();
    }

    /** Muda conta, valor e data no lugar, mantendo o ID (T2 do SPEC do fluxo). Só a viva. */
    void alterar(Movimentacao m) {
        var campos = Campos.de(m);
        jdbc.sql("UPDATE contas.movimentacoes SET conta_origem = :origem, conta_destino = :destino, valor = :valor, "
                        + "data = :data, updated_at = now() WHERE id = :id AND deleted_at IS NULL")
                .param("id", m.id().valor()).param("origem", campos.origem()).param("destino", campos.destino())
                .param("valor", m.valor().valor()).param("data", m.data())
                .update();
    }

    /** Exclusão lógica (RF13): a linha fica, com {@code deleted_at}; {@code updated_at} registra quando (T8). */
    void excluir(MovimentacaoId id) {
        jdbc.sql("UPDATE contas.movimentacoes SET deleted_at = now(), updated_at = now() "
                        + "WHERE id = :id AND deleted_at IS NULL")
                .param("id", id.valor()).update();
    }

    /** Desfaz a exclusão lógica (RF15). */
    void restaurar(MovimentacaoId id) {
        jdbc.sql("UPDATE contas.movimentacoes SET deleted_at = NULL, updated_at = now() "
                        + "WHERE id = :id AND deleted_at IS NOT NULL")
                .param("id", id.valor()).update();
    }

    /** Movimentações vivas da conta entre {@code de} e {@code ate}, inclusive, em ordem de data (RF12). */
    List<Movimentacao> doPeriodo(ContaId conta, LocalDate de, LocalDate ate) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM contas.movimentacoes "
                        + "WHERE (conta_origem = :conta OR conta_destino = :conta) "
                        + "AND data BETWEEN :de AND :ate AND deleted_at IS NULL ORDER BY data, created_at, id")
                .param("conta", conta.valor()).param("de", de).param("ate", ate)
                .query((rs, n) -> mapear(rs))
                .list();
    }

    private Resumo resumo(ContaId conta, LocalDate depoisDe, LocalDate ate) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN conta_destino = :conta THEN valor ELSE -valor END), 0) AS efeito,
                               COUNT(*) AS quantidade
                        FROM contas.movimentacoes
                        WHERE (conta_origem = :conta OR conta_destino = :conta)
                          AND data > :depoisDe AND data <= :ate AND deleted_at IS NULL""")
                .param("conta", conta.valor()).param("depoisDe", depoisDe).param("ate", ate)
                .query((rs, n) -> new Resumo(new Dinheiro(rs.getBigDecimal("efeito")), rs.getInt("quantidade")))
                .single();
    }

    private static Movimentacao mapear(ResultSet rs) throws SQLException {
        var id = new MovimentacaoId(rs.getObject("id", UUID.class));
        var origem = contaOuNulo(rs, "conta_origem");
        var destino = contaOuNulo(rs, "conta_destino");
        var valor = new Dinheiro(rs.getBigDecimal("valor"));
        var data = rs.getObject("data", LocalDate.class);
        return switch (TipoMovimentacao.valueOf(rs.getString("tipo"))) {
            case ENTRADA -> new Movimentacao.Entrada(id, destino, rs.getObject("ganho_id", UUID.class), valor, data);
            case SAIDA -> new Movimentacao.Saida(id, origem, rs.getObject("gasto_id", UUID.class), valor, data);
            case TRANSFERENCIA -> new Movimentacao.Transferencia(id, origem, destino, valor, data,
                    rs.getString("motivo"));
            case PAGAMENTO_FATURA -> new Movimentacao.PagamentoFatura(id, origem,
                    rs.getObject("cartao_id", UUID.class),
                    Competencia.doDia(rs.getObject("fatura_comp", LocalDate.class)), valor, data);
            case RENDIMENTO -> new Movimentacao.Rendimento(id, destino, valor, data);
        };
    }

    private static ContaId contaOuNulo(ResultSet rs, String coluna) throws SQLException {
        var valor = rs.getObject(coluna, UUID.class);
        return valor == null ? null : new ContaId(valor);
    }

    /** As colunas que variam por tipo. O {@code switch} sem {@code default} obriga a decidir cada tipo novo. */
    private record Campos(UUID origem, UUID destino, UUID ganho, UUID gasto, UUID cartao, LocalDate fatura,
                          String motivo) {
        static Campos de(Movimentacao m) {
            return switch (m) {
                case Movimentacao.Entrada e -> new Campos(null, e.destino().valor(), e.ganhoId(), null, null, null, null);
                case Movimentacao.Saida s -> new Campos(s.origem().valor(), null, null, s.gastoId(), null, null, null);
                case Movimentacao.Transferencia t -> new Campos(t.origem().valor(), t.destino().valor(), null, null,
                        null, null, t.motivo());
                case Movimentacao.PagamentoFatura p -> new Campos(p.origem().valor(), null, null, null, p.cartaoId(),
                        p.fatura().primeiroDia(), null);
                case Movimentacao.Rendimento r -> new Campos(null, r.destino().valor(), null, null, null, null, null);
            };
        }
    }
}
