package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.CategoriaId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL sobre o que um holerite gera dentro do {@code fluxo}: o ganho {@code SALARIO}, os recebimentos, os lançamentos
 * de FGTS e previdência e os gastos em folha. As tabelas são do mesmo módulo; o efeito em conta fica no serviço.
 * <p>
 * Toda gravação aceita a linha excluída de antes (mesma referência, T6) e a traz de volta, em vez de duplicar.
 */
@Repository
class EfeitosRepository {

    /** Uma linha de recebimento, de lançamento ou de gasto, viva ou excluída. A conta é nula no gasto. */
    record Linha(UUID id, Dinheiro valor, LocalDate data, ContaId conta, boolean excluida, boolean lancadoAntes) {

        boolean mesmosValores(Dinheiro valor, LocalDate data, ContaId conta) {
            return this.valor.equals(valor) && this.data.equals(data) && this.conta.equals(conta);
        }
    }

    private final JdbcClient jdbc;

    EfeitosRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- ganho SALARIO ----

    void salvarGanho(UUID id, Competencia competencia, Dinheiro bruto, Dinheiro liquido, HoleriteId holerite) {
        jdbc.sql("""
                        INSERT INTO fluxo.ganhos (id, competencia, descricao, tipo, bruto, liquido, holerite_id)
                        VALUES (:id, :competencia, :descricao, 'SALARIO', :bruto, :liquido, :holerite)
                        ON CONFLICT (id) DO UPDATE
                        SET bruto = EXCLUDED.bruto, liquido = EXCLUDED.liquido, deleted_at = NULL, updated_at = now()""")
                .param("id", id).param("competencia", competencia.mes().atDay(1))
                .param("descricao", "Salário " + competencia).param("bruto", bruto.valor())
                .param("liquido", liquido.valor()).param("holerite", holerite.valor())
                .update();
    }

    void excluirGanho(UUID id) {
        jdbc.sql("UPDATE fluxo.ganhos SET deleted_at = now(), updated_at = now() WHERE id = :id AND deleted_at IS NULL")
                .param("id", id).update();
    }

    // ---- recebimentos ----

    Optional<Linha> recebimento(UUID id) {
        return jdbc.sql("""
                        SELECT id, valor, data, conta_id, deleted_at IS NOT NULL AS excluida, lancado_antes
                        FROM fluxo.recebimentos WHERE id = :id""")
                .param("id", id).query(EfeitosRepository::linha).optional();
    }

    List<Linha> recebimentosVivos(UUID ganho) {
        return jdbc.sql("""
                        SELECT id, valor, data, conta_id, false AS excluida, lancado_antes
                        FROM fluxo.recebimentos WHERE ganho_id = :ganho AND deleted_at IS NULL""")
                .param("ganho", ganho).query(EfeitosRepository::linha).list();
    }

    void inserirRecebimento(UUID id, UUID ganho, Competencia competencia, String papel, Dinheiro valor,
                            LocalDate data, ContaId conta) {
        jdbc.sql("""
                        INSERT INTO fluxo.recebimentos (id, ganho_id, competencia, papel, valor, data, conta_id)
                        VALUES (:id, :ganho, :competencia, :papel, :valor, :data, :conta)""")
                .param("id", id).param("ganho", ganho).param("competencia", competencia.mes().atDay(1))
                .param("papel", papel).param("valor", valor.valor()).param("data", data)
                .param("conta", conta.valor())
                .update();
    }

    /** Associa ao ganho, atualiza os valores e traz de volta se estava excluído. */
    void atualizarRecebimento(UUID id, UUID ganho, Dinheiro valor, LocalDate data, ContaId conta) {
        jdbc.sql("""
                        UPDATE fluxo.recebimentos
                        SET ganho_id = :ganho, valor = :valor, data = :data, conta_id = :conta, deleted_at = NULL,
                            updated_at = now()
                        WHERE id = :id""")
                .param("id", id).param("ganho", ganho).param("valor", valor.valor()).param("data", data)
                .param("conta", conta.valor())
                .update();
    }

    void excluirRecebimento(UUID id) {
        jdbc.sql("UPDATE fluxo.recebimentos SET deleted_at = now(), updated_at = now() WHERE id = :id")
                .param("id", id).update();
    }

    /** O adiantamento lançado antes volta a ser solto: não é excluído com o holerite (RF34, casos de borda). */
    void soltarRecebimento(UUID id) {
        jdbc.sql("UPDATE fluxo.recebimentos SET ganho_id = NULL, updated_at = now() WHERE id = :id")
                .param("id", id).update();
    }

    // ---- FGTS e previdência ----

    Optional<Linha> lancamento(UUID id) {
        return jdbc.sql("""
                        SELECT id, valor, data, conta_id, deleted_at IS NOT NULL AS excluida, false AS lancado_antes
                        FROM fluxo.lancamentos_folha WHERE id = :id""")
                .param("id", id).query(EfeitosRepository::linha).optional();
    }

    List<Linha> lancamentosVivos(HoleriteId holerite) {
        return jdbc.sql("""
                        SELECT id, valor, data, conta_id, false AS excluida, false AS lancado_antes
                        FROM fluxo.lancamentos_folha WHERE holerite_id = :h AND deleted_at IS NULL""")
                .param("h", holerite.valor()).query(EfeitosRepository::linha).list();
    }

    void inserirLancamento(UUID id, HoleriteId holerite, String papel, Dinheiro valor, LocalDate data,
                           ContaId conta) {
        jdbc.sql("""
                        INSERT INTO fluxo.lancamentos_folha (id, holerite_id, papel, valor, data, conta_id)
                        VALUES (:id, :h, :papel, :valor, :data, :conta)""")
                .param("id", id).param("h", holerite.valor()).param("papel", papel).param("valor", valor.valor())
                .param("data", data).param("conta", conta.valor())
                .update();
    }

    void atualizarLancamento(UUID id, Dinheiro valor, LocalDate data, ContaId conta) {
        jdbc.sql("""
                        UPDATE fluxo.lancamentos_folha
                        SET valor = :valor, data = :data, conta_id = :conta, deleted_at = NULL, updated_at = now()
                        WHERE id = :id""")
                .param("id", id).param("valor", valor.valor()).param("data", data).param("conta", conta.valor())
                .update();
    }

    void excluirLancamento(UUID id) {
        jdbc.sql("UPDATE fluxo.lancamentos_folha SET deleted_at = now(), updated_at = now() WHERE id = :id")
                .param("id", id).update();
    }

    // ---- gastos em folha ----

    Optional<Linha> gasto(UUID id) {
        return jdbc.sql("""
                        SELECT id, valor, data, NULL::uuid AS conta_id, deleted_at IS NOT NULL AS excluida,
                               false AS lancado_antes
                        FROM fluxo.gastos WHERE id = :id""")
                .param("id", id).query(EfeitosRepository::linha).optional();
    }

    List<UUID> gastosVivos(HoleriteId holerite) {
        return jdbc.sql("SELECT id FROM fluxo.gastos WHERE holerite_id = :h AND deleted_at IS NULL")
                .param("h", holerite.valor()).query(UUID.class).list();
    }

    void inserirGasto(UUID id, HoleriteId holerite, String descricao, Dinheiro valor, LocalDate data,
                      CategoriaId categoria) {
        jdbc.sql("""
                        INSERT INTO fluxo.gastos (id, data, descricao, valor, categoria_id, forma, holerite_id)
                        VALUES (:id, :data, :descricao, :valor, :categoria, 'DESCONTO_EM_FOLHA', :h)""")
                .param("id", id).param("data", data).param("descricao", descricao).param("valor", valor.valor())
                .param("categoria", categoria.valor()).param("h", holerite.valor())
                .update();
    }

    /**
     * Atualiza valor, data e descrição. A categoria do gasto vivo é preservada, porque o usuário pode tê-la trocado
     * pela API de gastos (RF15); a do catálogo só vale para o gasto que volta da exclusão.
     */
    void atualizarGasto(UUID id, String descricao, Dinheiro valor, LocalDate data, CategoriaId categoria) {
        jdbc.sql("""
                        UPDATE fluxo.gastos
                        SET descricao = :descricao, valor = :valor, data = :data,
                            categoria_id = CASE WHEN deleted_at IS NULL THEN categoria_id ELSE :categoria END,
                            deleted_at = NULL, updated_at = now()
                        WHERE id = :id""")
                .param("id", id).param("descricao", descricao).param("valor", valor.valor()).param("data", data)
                .param("categoria", categoria.valor())
                .update();
    }

    void excluirGasto(UUID id) {
        jdbc.sql("UPDATE fluxo.gastos SET deleted_at = now(), updated_at = now() WHERE id = :id")
                .param("id", id).update();
    }

    private static Linha linha(ResultSet rs, int n) throws SQLException {
        var conta = rs.getObject("conta_id", UUID.class);
        return new Linha(rs.getObject("id", UUID.class), new Dinheiro(rs.getBigDecimal("valor")),
                rs.getObject("data", LocalDate.class), conta == null ? null : new ContaId(conta),
                rs.getBoolean("excluida"), rs.getBoolean("lancado_antes"));
    }
}
