package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL explícito sobre {@code contas.contas}. O índice único de apelido vira {@link ApelidoEmUsoException} (RN09). */
@Repository
class ContaRepository {

    private static final String COLUNAS = "id, apelido, instituicao, tipo, funcao, limite_cheque_especial, ativa";

    private final JdbcClient jdbc;

    ContaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    ContaId inserir(String apelido, String instituicao, TipoConta tipo, Funcao funcao, Dinheiro limite) {
        try {
            var id = jdbc.sql("""
                            INSERT INTO contas.contas (apelido, instituicao, tipo, funcao, limite_cheque_especial)
                            VALUES (:apelido, :instituicao, :tipo, :funcao, :limite) RETURNING id""")
                    .param("apelido", apelido).param("instituicao", instituicao)
                    .param("tipo", tipo.name()).param("funcao", funcao.name()).param("limite", limite.valor())
                    .query(UUID.class).single();
            return new ContaId(id);
        } catch (DuplicateKeyException e) {
            throw new ApelidoEmUsoException(apelido);
        }
    }

    Optional<Conta> buscar(ContaId id) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM contas.contas WHERE id = :id")
                .param("id", id.valor())
                .query(ContaRepository::mapear)
                .optional();
    }

    List<Conta> listar(boolean incluirInativas) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM contas.contas WHERE ativa OR :todas ORDER BY lower(apelido)")
                .param("todas", incluirInativas)
                .query(ContaRepository::mapear)
                .list();
    }

    /** O tipo nunca muda (RF04); {@code updated_at} é preenchido aqui, não por trigger (T8). */
    void atualizar(Conta conta) {
        try {
            jdbc.sql("""
                            UPDATE contas.contas
                            SET apelido = :apelido, instituicao = :instituicao, funcao = :funcao,
                                limite_cheque_especial = :limite, ativa = :ativa, updated_at = now()
                            WHERE id = :id""")
                    .param("id", conta.id().valor()).param("apelido", conta.apelido())
                    .param("instituicao", conta.instituicao()).param("funcao", conta.funcao().name())
                    .param("limite", conta.limiteChequeEspecial().valor()).param("ativa", conta.ativa())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApelidoEmUsoException(conta.apelido());
        }
    }

    private static Conta mapear(ResultSet rs, int linha) throws SQLException {
        return new Conta(new ContaId(rs.getObject("id", UUID.class)), rs.getString("apelido"),
                rs.getString("instituicao"), TipoConta.valueOf(rs.getString("tipo")),
                Funcao.valueOf(rs.getString("funcao")), new Dinheiro(rs.getBigDecimal("limite_cheque_especial")),
                rs.getBoolean("ativa"));
    }
}
