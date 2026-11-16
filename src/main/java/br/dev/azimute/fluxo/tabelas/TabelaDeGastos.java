package br.dev.azimute.fluxo.tabelas;

import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import br.dev.azimute.fluxo.CategoriaId;
import br.dev.azimute.fluxo.folha.HoleriteId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Escrita em {@code fluxo.gastos}, usada pela API de gastos e pelo holerite. */
@Repository
public class TabelaDeGastos {

    /**
     * Um gasto a gravar. Pago à vista tem conta; descontado em folha tem holerite; nunca os dois. A forma sai daí, e o
     * CHECK {@code forma_coerente} do banco garante o mesmo.
     */
    public record Linha(UUID id, LocalDate data, String descricao, Dinheiro valor, CategoriaId categoria,
                        ContaId conta, HoleriteId holerite) {

        public Linha {
            Objects.requireNonNull(id, "id");
            if ((conta == null) == (holerite == null)) {
                throw new IllegalArgumentException("Gasto tem conta ou holerite, nunca os dois nem nenhum");
            }
        }

        String forma() {
            return holerite == null ? "A_VISTA" : "DESCONTO_EM_FOLHA";
        }
    }

    private final JdbcClient jdbc;

    TabelaDeGastos(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Grava com o ID dado. {@code false} quando o ID já existe, vivo ou excluído; quem decide o que fazer é quem
     * chamou. {@code ON CONFLICT DO NOTHING} resolve a corrida entre dois envios simultâneos.
     */
    public boolean inserirSeAusente(Linha g) {
        return jdbc.sql("""
                        INSERT INTO fluxo.gastos (id, data, descricao, valor, categoria_id, forma, conta_id, holerite_id)
                        VALUES (:id, :data, :descricao, :valor, :categoria, :forma, :conta, :holerite)
                        ON CONFLICT DO NOTHING""")
                .param("id", g.id()).param("data", g.data()).param("descricao", g.descricao())
                .param("valor", g.valor().valor()).param("categoria", g.categoria().valor())
                .param("forma", g.forma())
                .param("conta", g.conta() == null ? null : g.conta().valor())
                .param("holerite", g.holerite() == null ? null : g.holerite().valor())
                .update() == 1;
    }

    /** Exclusão lógica do gasto vivo; o já excluído fica como está. */
    public void excluir(UUID id) {
        jdbc.sql("UPDATE fluxo.gastos SET deleted_at = now(), updated_at = now() WHERE id = :id AND deleted_at IS NULL")
                .param("id", id).update();
    }
}
