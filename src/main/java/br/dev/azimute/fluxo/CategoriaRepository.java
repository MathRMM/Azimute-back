package br.dev.azimute.fluxo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL sobre {@code fluxo.categorias}. O índice único de nome vira {@link NomeDeCategoriaEmUsoException} (RN06). */
@Repository
class CategoriaRepository {

    private static final String COLUNAS = "id, nome, predefinida, ativa";

    private final JdbcClient jdbc;

    CategoriaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Categoria inserir(String nome) {
        try {
            return jdbc.sql("INSERT INTO fluxo.categorias (nome) VALUES (:nome) RETURNING " + COLUNAS)
                    .param("nome", nome).query(CategoriaRepository::mapear).single();
        } catch (DuplicateKeyException e) {
            throw new NomeDeCategoriaEmUsoException(nome);
        }
    }

    Optional<Categoria> buscar(CategoriaId id) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM fluxo.categorias WHERE id = :id")
                .param("id", id.valor()).query(CategoriaRepository::mapear).optional();
    }

    /** Em ordem alfabética (RF03). */
    List<Categoria> listar(boolean incluirInativas) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM fluxo.categorias WHERE ativa OR :todas ORDER BY lower(nome)")
                .param("todas", incluirInativas).query(CategoriaRepository::mapear).list();
    }

    void atualizar(Categoria categoria) {
        try {
            jdbc.sql("UPDATE fluxo.categorias SET nome = :nome, ativa = :ativa, updated_at = now() WHERE id = :id")
                    .param("id", categoria.id().valor()).param("nome", categoria.nome())
                    .param("ativa", categoria.ativa()).update();
        } catch (DuplicateKeyException e) {
            throw new NomeDeCategoriaEmUsoException(categoria.nome());
        }
    }

    private static Categoria mapear(ResultSet rs, int linha) throws SQLException {
        return new Categoria(new CategoriaId(rs.getObject("id", UUID.class)), rs.getString("nome"),
                rs.getBoolean("predefinida"), rs.getBoolean("ativa"));
    }
}
