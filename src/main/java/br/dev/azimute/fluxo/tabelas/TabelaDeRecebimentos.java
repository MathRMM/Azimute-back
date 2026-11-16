package br.dev.azimute.fluxo.tabelas;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContaId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Escrita em {@code fluxo.recebimentos}, usada pela API de ganhos, pelo holerite e pelo adiantamento. */
@Repository
public class TabelaDeRecebimentos {

    /**
     * Um recebimento a gravar. Sem ganho só o adiantamento lançado antes do holerite ({@code lancadoAntes}), que o
     * holerite associa depois (RF34).
     */
    public record Linha(UUID id, UUID ganho, Competencia competencia, String papel, Dinheiro valor, LocalDate data,
                        ContaId conta, boolean lancadoAntes) {

        public Linha {
            Objects.requireNonNull(id, "id");
        }
    }

    private final JdbcClient jdbc;

    TabelaDeRecebimentos(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void inserir(Linha r) {
        jdbc.sql("""
                        INSERT INTO fluxo.recebimentos (id, ganho_id, competencia, papel, valor, data, conta_id,
                                                        lancado_antes)
                        VALUES (:id, :ganho, :competencia, :papel, :valor, :data, :conta, :antes)""")
                .param("id", r.id()).param("ganho", r.ganho()).param("competencia", r.competencia().primeiroDia())
                .param("papel", r.papel()).param("valor", r.valor().valor()).param("data", r.data())
                .param("conta", r.conta().valor()).param("antes", r.lancadoAntes())
                .update();
    }

    /** Exclusão lógica do recebimento vivo; o já excluído fica como está. */
    public void excluir(UUID id) {
        jdbc.sql("""
                        UPDATE fluxo.recebimentos SET deleted_at = now(), updated_at = now()
                        WHERE id = :id AND deleted_at IS NULL""")
                .param("id", id).update();
    }
}
