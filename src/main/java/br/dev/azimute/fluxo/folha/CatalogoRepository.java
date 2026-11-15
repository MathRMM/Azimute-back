package br.dev.azimute.fluxo.folha;

import br.dev.azimute.fluxo.CategoriaId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** SQL sobre {@code fluxo.rubricas_catalogo} (RF35, RF36, T11). */
@Repository
class CatalogoRepository {

    private static final String COLUNAS = "codigo, descricao, natureza, categoria_id, a_classificar";

    private final JdbcClient jdbc;

    CatalogoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<RubricaCatalogo> listar() {
        return jdbc.sql("SELECT " + COLUNAS + " FROM fluxo.rubricas_catalogo ORDER BY codigo")
                .query(CatalogoRepository::mapear).list();
    }

    Map<String, RubricaCatalogo> mapa() {
        var mapa = new LinkedHashMap<String, RubricaCatalogo>();
        listar().forEach(r -> mapa.put(r.codigo(), r));
        return mapa;
    }

    Optional<RubricaCatalogo> buscar(String codigo) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM fluxo.rubricas_catalogo WHERE codigo = :codigo")
                .param("codigo", codigo).query(CatalogoRepository::mapear).optional();
    }

    /** Rubrica nova: entra com a natureza provisória e marcada como a classificar (RF36). */
    void inserirAClassificar(String codigo, String descricao, NaturezaRubrica natureza) {
        jdbc.sql("""
                        INSERT INTO fluxo.rubricas_catalogo (codigo, descricao, natureza, a_classificar)
                        VALUES (:codigo, :descricao, :natureza, true)
                        ON CONFLICT (codigo) DO NOTHING""")
                .param("codigo", codigo).param("descricao", descricao).param("natureza", natureza.name())
                .update();
    }

    void reclassificar(String codigo, NaturezaRubrica natureza, CategoriaId categoria) {
        jdbc.sql("""
                        UPDATE fluxo.rubricas_catalogo
                        SET natureza = :natureza, categoria_id = :categoria, a_classificar = false, updated_at = now()
                        WHERE codigo = :codigo""")
                .param("codigo", codigo).param("natureza", natureza.name())
                .param("categoria", categoria == null ? null : categoria.valor())
                .update();
    }

    /** Vazio quando a categoria não existe; senão, se está ativa. */
    Optional<Boolean> categoriaAtiva(CategoriaId categoria) {
        return jdbc.sql("SELECT ativa FROM fluxo.categorias WHERE id = :id")
                .param("id", categoria.valor()).query(Boolean.class).optional();
    }

    private static RubricaCatalogo mapear(ResultSet rs, int linha) throws SQLException {
        var categoria = rs.getObject("categoria_id", UUID.class);
        return new RubricaCatalogo(rs.getString("codigo"), rs.getString("descricao"),
                NaturezaRubrica.valueOf(rs.getString("natureza")),
                categoria == null ? null : new CategoriaId(categoria), rs.getBoolean("a_classificar"));
    }
}
