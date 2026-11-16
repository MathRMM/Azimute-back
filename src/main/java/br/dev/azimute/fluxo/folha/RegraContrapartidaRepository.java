package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Taxa;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** SQL sobre {@code fluxo.regras_contrapartida}: só leitura e inserção; o banco impede alterar (T7). */
@Repository
class RegraContrapartidaRepository {

    private final JdbcClient jdbc;

    RegraContrapartidaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<RegraContrapartida> listar() {
        return jdbc.sql("SELECT a_partir_de, percentual_da_basica FROM fluxo.regras_contrapartida ORDER BY a_partir_de")
                .query((rs, n) -> new RegraContrapartida(
                        Competencia.doDia(rs.getObject("a_partir_de", LocalDate.class)),
                        Taxa.deFracao(rs.getBigDecimal("percentual_da_basica"))))
                .list();
    }

    void inserir(RegraContrapartida regra) {
        try {
            jdbc.sql("INSERT INTO fluxo.regras_contrapartida (a_partir_de, percentual_da_basica) VALUES (:a, :p)")
                    .param("a", regra.aPartirDe().primeiroDia()).param("p", regra.percentualDaBasica().fracao())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new RegraDeContrapartidaExistenteException(regra.aPartirDe());
        }
    }
}
