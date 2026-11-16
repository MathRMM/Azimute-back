package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.comum.Identificador;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.CategoriaId;
import br.dev.azimute.fluxo.GanhoId;
import br.dev.azimute.fluxo.GastoId;
import br.dev.azimute.fluxo.tabelas.RecebimentoId;
import br.dev.azimute.fluxo.tabelas.TabelaDeGanhos;
import br.dev.azimute.fluxo.tabelas.TabelaDeGastos;
import br.dev.azimute.fluxo.tabelas.TabelaDeRecebimentos;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
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
    record Linha<I extends Identificador>(I id, Dinheiro valor, LocalDate data, ContaId conta, boolean excluida, boolean lancadoAntes) {

        boolean mesmosValores(Dinheiro valor, LocalDate data, ContaId conta) {
            return this.valor.equals(valor) && this.data.equals(data) && this.conta.equals(conta);
        }
    }

    private final JdbcClient jdbc;
    private final TabelaDeGanhos ganhos;
    private final TabelaDeRecebimentos recebimentos;
    private final TabelaDeGastos gastos;

    EfeitosRepository(JdbcClient jdbc, TabelaDeGanhos ganhos, TabelaDeRecebimentos recebimentos,
                      TabelaDeGastos gastos) {
        this.jdbc = jdbc;
        this.ganhos = ganhos;
        this.recebimentos = recebimentos;
        this.gastos = gastos;
    }

    // ---- ganho SALARIO ----

    void salvarGanho(GanhoId id, Competencia competencia, Dinheiro bruto, Dinheiro liquido, HoleriteId holerite) {
        ganhos.inserirOuReviver(new TabelaDeGanhos.Linha(id, competencia, "Salário " + competencia, "SALARIO", bruto,
                liquido, holerite));
    }

    void excluirGanho(GanhoId id) {
        ganhos.excluir(id);
    }

    // ---- recebimentos ----

    Optional<Linha<RecebimentoId>> recebimento(RecebimentoId id) {
        return jdbc.sql("""
                        SELECT id, valor, data, conta_id, deleted_at IS NOT NULL AS excluida, lancado_antes
                        FROM fluxo.recebimentos WHERE id = :id""")
                .param("id", id.valor()).query((rs, n) -> linha(rs, RecebimentoId::new)).optional();
    }

    List<Linha<RecebimentoId>> recebimentosVivos(GanhoId ganho) {
        return jdbc.sql("""
                        SELECT id, valor, data, conta_id, false AS excluida, lancado_antes
                        FROM fluxo.recebimentos WHERE ganho_id = :ganho AND deleted_at IS NULL""")
                .param("ganho", ganho.valor()).query((rs, n) -> linha(rs, RecebimentoId::new)).list();
    }

    void inserirRecebimento(RecebimentoId id, GanhoId ganho, Competencia competencia, String papel, Dinheiro valor,
                            LocalDate data, ContaId conta) {
        recebimentos.inserir(new TabelaDeRecebimentos.Linha(id, ganho, competencia, papel, valor, data, conta, false));
    }

    /** Associa ao ganho, atualiza os valores e traz de volta se estava excluído. */
    void atualizarRecebimento(RecebimentoId id, GanhoId ganho, Dinheiro valor, LocalDate data, ContaId conta) {
        jdbc.sql("""
                        UPDATE fluxo.recebimentos
                        SET ganho_id = :ganho, valor = :valor, data = :data, conta_id = :conta, deleted_at = NULL,
                            updated_at = now()
                        WHERE id = :id""")
                .param("id", id.valor()).param("ganho", ganho.valor()).param("valor", valor.valor()).param("data", data)
                .param("conta", conta.valor())
                .update();
    }

    void excluirRecebimento(RecebimentoId id) {
        recebimentos.excluir(id);
    }

    /** O adiantamento lançado antes volta a ser solto: não é excluído com o holerite (RF34, casos de borda). */
    void soltarRecebimento(RecebimentoId id) {
        jdbc.sql("UPDATE fluxo.recebimentos SET ganho_id = NULL, updated_at = now() WHERE id = :id")
                .param("id", id.valor()).update();
    }

    // ---- FGTS e previdência ----

    Optional<Linha<LancamentoDaFolhaId>> lancamento(LancamentoDaFolhaId id) {
        return jdbc.sql("""
                        SELECT id, valor, data, conta_id, deleted_at IS NOT NULL AS excluida, false AS lancado_antes
                        FROM fluxo.lancamentos_folha WHERE id = :id""")
                .param("id", id.valor()).query((rs, n) -> linha(rs, LancamentoDaFolhaId::new)).optional();
    }

    List<Linha<LancamentoDaFolhaId>> lancamentosVivos(HoleriteId holerite) {
        return jdbc.sql("""
                        SELECT id, valor, data, conta_id, false AS excluida, false AS lancado_antes
                        FROM fluxo.lancamentos_folha WHERE holerite_id = :h AND deleted_at IS NULL""")
                .param("h", holerite.valor()).query((rs, n) -> linha(rs, LancamentoDaFolhaId::new)).list();
    }

    void inserirLancamento(LancamentoDaFolhaId id, HoleriteId holerite, String papel, Dinheiro valor, LocalDate data,
                           ContaId conta) {
        jdbc.sql("""
                        INSERT INTO fluxo.lancamentos_folha (id, holerite_id, papel, valor, data, conta_id)
                        VALUES (:id, :h, :papel, :valor, :data, :conta)""")
                .param("id", id.valor()).param("h", holerite.valor()).param("papel", papel).param("valor", valor.valor())
                .param("data", data).param("conta", conta.valor())
                .update();
    }

    void atualizarLancamento(LancamentoDaFolhaId id, Dinheiro valor, LocalDate data, ContaId conta) {
        jdbc.sql("""
                        UPDATE fluxo.lancamentos_folha
                        SET valor = :valor, data = :data, conta_id = :conta, deleted_at = NULL, updated_at = now()
                        WHERE id = :id""")
                .param("id", id.valor()).param("valor", valor.valor()).param("data", data).param("conta", conta.valor())
                .update();
    }

    void excluirLancamento(LancamentoDaFolhaId id) {
        jdbc.sql("UPDATE fluxo.lancamentos_folha SET deleted_at = now(), updated_at = now() WHERE id = :id")
                .param("id", id.valor()).update();
    }

    // ---- gastos em folha ----

    Optional<Linha<GastoId>> gasto(GastoId id) {
        return jdbc.sql("""
                        SELECT id, valor, data, NULL::uuid AS conta_id, deleted_at IS NOT NULL AS excluida,
                               false AS lancado_antes
                        FROM fluxo.gastos WHERE id = :id""")
                .param("id", id.valor()).query((rs, n) -> linha(rs, GastoId::new)).optional();
    }

    List<GastoId> gastosVivos(HoleriteId holerite) {
        return jdbc.sql("SELECT id FROM fluxo.gastos WHERE holerite_id = :h AND deleted_at IS NULL")
                .param("h", holerite.valor()).query((rs, n) -> new GastoId(rs.getObject("id", UUID.class))).list();
    }

    void inserirGasto(GastoId id, HoleriteId holerite, String descricao, Dinheiro valor, LocalDate data,
                      CategoriaId categoria) {
        gastos.inserirSeAusente(new TabelaDeGastos.Linha(id, data, descricao, valor, categoria, null, holerite));
    }

    /**
     * Atualiza valor, data e descrição. A categoria do gasto vivo é preservada, porque o usuário pode tê-la trocado
     * pela API de gastos (RF15); a do catálogo só vale para o gasto que volta da exclusão.
     */
    void atualizarGasto(GastoId id, String descricao, Dinheiro valor, LocalDate data, CategoriaId categoria) {
        jdbc.sql("""
                        UPDATE fluxo.gastos
                        SET descricao = :descricao, valor = :valor, data = :data,
                            categoria_id = CASE WHEN deleted_at IS NULL THEN categoria_id ELSE :categoria END,
                            deleted_at = NULL, updated_at = now()
                        WHERE id = :id""")
                .param("id", id.valor()).param("descricao", descricao).param("valor", valor.valor()).param("data", data)
                .param("categoria", categoria.valor())
                .update();
    }

    void excluirGasto(GastoId id) {
        gastos.excluir(id);
    }

    private static <I extends Identificador> Linha<I> linha(ResultSet rs, Function<UUID, I> id) throws SQLException {
        var conta = rs.getObject("conta_id", UUID.class);
        return new Linha<>(id.apply(rs.getObject("id", UUID.class)), new Dinheiro(rs.getBigDecimal("valor")),
                rs.getObject("data", LocalDate.class), conta == null ? null : new ContaId(conta),
                rs.getBoolean("excluida"), rs.getBoolean("lancado_antes"));
    }
}
