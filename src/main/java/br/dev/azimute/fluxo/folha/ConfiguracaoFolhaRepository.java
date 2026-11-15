package br.dev.azimute.fluxo.folha;

import br.dev.azimute.contas.ContaId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** SQL sobre {@code fluxo.configuracao_folha}, uma linha só. */
@Repository
class ConfiguracaoFolhaRepository {

    private final JdbcClient jdbc;

    ConfiguracaoFolhaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ConfiguracaoFolha> buscar() {
        return jdbc.sql("SELECT conta_salario, conta_fgts, conta_previdencia FROM fluxo.configuracao_folha")
                .query((rs, n) -> new ConfiguracaoFolha(new ContaId(rs.getObject("conta_salario", UUID.class)),
                        new ContaId(rs.getObject("conta_fgts", UUID.class)),
                        new ContaId(rs.getObject("conta_previdencia", UUID.class))))
                .optional();
    }

    void salvar(ConfiguracaoFolha c) {
        jdbc.sql("""
                        INSERT INTO fluxo.configuracao_folha (conta_salario, conta_fgts, conta_previdencia)
                        VALUES (:salario, :fgts, :previdencia)
                        ON CONFLICT (id) DO UPDATE
                        SET conta_salario = EXCLUDED.conta_salario, conta_fgts = EXCLUDED.conta_fgts,
                            conta_previdencia = EXCLUDED.conta_previdencia, updated_at = now()""")
                .param("salario", c.salario().valor()).param("fgts", c.fgts().valor())
                .param("previdencia", c.previdencia().valor())
                .update();
    }
}
