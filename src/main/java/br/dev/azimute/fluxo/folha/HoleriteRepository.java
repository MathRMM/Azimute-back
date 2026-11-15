package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL sobre {@code fluxo.holerites} e {@code fluxo.holerite_rubricas}. As rubricas ficam exatamente como vieram; o
 * índice único entre os vivos garante um holerite por competência (T9).
 */
@Repository
class HoleriteRepository {

    /** O holerite e se ele está excluído. */
    record Registro(Holerite holerite, boolean excluido) {}

    private static final String COLUNAS = """
            id, competencia, origem, hash, total_vencimentos, total_descontos, liquido, salario_base, base_inss,
            base_fgts, fgts_mes, base_irrf, contrapartida_previdencia, data_adiantamento, data_liquido,
            deleted_at IS NOT NULL AS excluido""";

    private final JdbcClient jdbc;

    HoleriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void inserir(Holerite h, List<RubricaLida> rubricas) {
        var b = h.bases();
        try {
            jdbc.sql("""
                            INSERT INTO fluxo.holerites (id, competencia, origem, hash, total_vencimentos, total_descontos,
                                liquido, salario_base, base_inss, base_fgts, fgts_mes, base_irrf,
                                contrapartida_previdencia, data_adiantamento, data_liquido)
                            VALUES (:id, :competencia, :origem, :hash, :vencimentos, :descontos, :liquido, :salarioBase,
                                :baseInss, :baseFgts, :fgtsMes, :baseIrrf, :contrapartida, :adiantamento, :dataLiquido)""")
                    .param("id", h.id().valor()).param("competencia", h.competencia().mes().atDay(1))
                    .param("origem", h.origem().name()).param("hash", h.hash())
                    .param("vencimentos", h.totalVencimentos().valor()).param("descontos", h.totalDescontos().valor())
                    .param("liquido", h.liquido().valor())
                    .param("salarioBase", valor(b.salarioBase())).param("baseInss", valor(b.baseInss()))
                    .param("baseFgts", valor(b.baseFgts())).param("fgtsMes", valor(b.fgtsMes()))
                    .param("baseIrrf", valor(b.baseIrrf()))
                    .param("contrapartida", h.contrapartidaPrevidencia().valor())
                    .param("adiantamento", h.dataAdiantamento()).param("dataLiquido", h.dataLiquido())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new HoleriteJaImportadoException(h.competencia());
        }
        for (int i = 0; i < rubricas.size(); i++) {
            var r = rubricas.get(i);
            jdbc.sql("""
                            INSERT INTO fluxo.holerite_rubricas (holerite_id, ordem, codigo, descricao, referencia,
                                                                 vencimento, desconto)
                            VALUES (:h, :ordem, :codigo, :descricao, :referencia, :vencimento, :desconto)""")
                    .param("h", h.id().valor()).param("ordem", i + 1).param("codigo", r.codigo())
                    .param("descricao", r.descricao()).param("referencia", r.referencia())
                    .param("vencimento", valor(r.vencimento())).param("desconto", valor(r.desconto()))
                    .update();
        }
    }

    Optional<Registro> buscar(HoleriteId id) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM fluxo.holerites WHERE id = :id")
                .param("id", id.valor()).query(HoleriteRepository::mapear).optional();
    }

    /** O holerite com esse conteúdo: o vivo, se houver; senão o excluído mais recente (RN11, T9). */
    Optional<Registro> buscarPorHash(String hash) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM fluxo.holerites WHERE hash = :hash "
                        + "ORDER BY deleted_at IS NULL DESC, deleted_at DESC LIMIT 1")
                .param("hash", hash).query(HoleriteRepository::mapear).optional();
    }

    boolean existeVivo(Competencia competencia) {
        return jdbc.sql("SELECT count(*) FROM fluxo.holerites WHERE competencia = :c AND deleted_at IS NULL")
                .param("c", competencia.mes().atDay(1)).query(Long.class).single() > 0;
    }

    List<RubricaLida> rubricas(HoleriteId id) {
        return jdbc.sql("""
                        SELECT codigo, descricao, referencia, vencimento, desconto FROM fluxo.holerite_rubricas
                        WHERE holerite_id = :h ORDER BY ordem""")
                .param("h", id.valor())
                .query((rs, n) -> new RubricaLida(rs.getString("codigo"), rs.getString("descricao"),
                        rs.getBigDecimal("referencia"), dinheiro(rs.getBigDecimal("vencimento")),
                        dinheiro(rs.getBigDecimal("desconto"))))
                .list();
    }

    /** Holerites vivos que usam a rubrica: os que a reclassificação reprocessa (RN13). */
    List<HoleriteId> vivosComRubrica(String codigo) {
        return jdbc.sql("""
                        SELECT DISTINCT h.id FROM fluxo.holerites h
                        JOIN fluxo.holerite_rubricas r ON r.holerite_id = h.id
                        WHERE r.codigo = :codigo AND h.deleted_at IS NULL""")
                .param("codigo", codigo).query(UUID.class).list()
                .stream().map(HoleriteId::new).toList();
    }

    void excluir(HoleriteId id) {
        jdbc.sql("UPDATE fluxo.holerites SET deleted_at = now(), updated_at = now() WHERE id = :id")
                .param("id", id.valor()).update();
    }

    /** Outro holerite vivo na mesma competência impede a restauração (T9). */
    void restaurar(Holerite h) {
        try {
            jdbc.sql("UPDATE fluxo.holerites SET deleted_at = NULL, updated_at = now() WHERE id = :id")
                    .param("id", h.id().valor()).update();
        } catch (DuplicateKeyException e) {
            throw new HoleriteJaImportadoException(h.competencia());
        }
    }

    /** T8: o PDF fica à parte, para a listagem não carregar o arquivo. */
    void salvarArquivo(HoleriteId id, byte[] conteudo) {
        jdbc.sql("INSERT INTO fluxo.holerite_arquivos (holerite_id, conteudo) VALUES (:h, :conteudo)")
                .param("h", id.valor()).param("conteudo", conteudo).update();
    }

    Optional<byte[]> arquivo(HoleriteId id) {
        return jdbc.sql("SELECT conteudo FROM fluxo.holerite_arquivos WHERE holerite_id = :h")
                .param("h", id.valor()).query((rs, n) -> rs.getBytes("conteudo")).optional();
    }

    /** Holerites vivos do ano, em ordem de competência (RF37). */
    List<Holerite> listar(int ano) {
        return jdbc.sql("SELECT " + COLUNAS + """
                         FROM fluxo.holerites
                        WHERE deleted_at IS NULL AND competencia >= :inicio AND competencia < :fim
                        ORDER BY competencia""")
                .param("inicio", LocalDate.of(ano, 1, 1)).param("fim", LocalDate.of(ano + 1, 1, 1))
                .query(HoleriteRepository::mapear).list()
                .stream().map(Registro::holerite).toList();
    }

    /** RF35b: só a correção explícita muda a contrapartida gravada (RN19). */
    void alterarContrapartida(HoleriteId id, Dinheiro contrapartida) {
        jdbc.sql("UPDATE fluxo.holerites SET contrapartida_previdencia = :c, updated_at = now() WHERE id = :id")
                .param("id", id.valor()).param("c", contrapartida.valor()).update();
    }

    private static Registro mapear(ResultSet rs, int linha) throws SQLException {
        var bases = new BasesLidas(dinheiro(rs.getBigDecimal("salario_base")), dinheiro(rs.getBigDecimal("base_inss")),
                dinheiro(rs.getBigDecimal("base_fgts")), dinheiro(rs.getBigDecimal("fgts_mes")),
                dinheiro(rs.getBigDecimal("base_irrf")));
        var holerite = new Holerite(new HoleriteId(rs.getObject("id", UUID.class)),
                new Competencia(YearMonth.from(rs.getObject("competencia", LocalDate.class))),
                Holerite.Origem.valueOf(rs.getString("origem")), rs.getString("hash"),
                new Dinheiro(rs.getBigDecimal("total_vencimentos")), new Dinheiro(rs.getBigDecimal("total_descontos")),
                new Dinheiro(rs.getBigDecimal("liquido")), bases,
                new Dinheiro(rs.getBigDecimal("contrapartida_previdencia")),
                rs.getObject("data_adiantamento", LocalDate.class), rs.getObject("data_liquido", LocalDate.class));
        return new Registro(holerite, rs.getBoolean("excluido"));
    }

    private static Dinheiro dinheiro(BigDecimal valor) {
        return valor == null ? null : new Dinheiro(valor);
    }

    private static BigDecimal valor(Dinheiro dinheiro) {
        return dinheiro == null ? null : dinheiro.valor();
    }
}
