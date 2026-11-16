package br.dev.azimute.fluxo;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cria contas, categorias, holerites, gastos e ganhos direto no banco para os testes de integração do {@code fluxo}.
 * Nomes e competências são aleatórios: o banco é compartilhado entre as classes de teste e há índices únicos.
 */
final class MontadorDeFluxo {

    static final LocalDate HOJE = LocalDate.of(2026, 11, 14);

    private final JdbcClient jdbc;

    MontadorDeFluxo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID conta() {
        return contaDoTipo("CORRENTE");
    }

    UUID contaDoTipo(String tipo) {
        return jdbc.sql("""
                        INSERT INTO contas.contas (apelido, instituicao, tipo, funcao)
                        VALUES (:apelido, 'Banco', :tipo, 'GIRO') RETURNING id""")
                .param("apelido", "Conta " + UUID.randomUUID()).param("tipo", tipo)
                .query(UUID.class).single();
    }

    UUID categoria() {
        return categoria("Categoria " + UUID.randomUUID());
    }

    UUID categoria(String nome) {
        return jdbc.sql("INSERT INTO fluxo.categorias (nome) VALUES (:nome) RETURNING id")
                .param("nome", nome).query(UUID.class).single();
    }

    /** Competência aleatória num ano distante, para não colidir com o índice único entre os vivos. */
    static LocalDate competenciaLivre() {
        return LocalDate.of(ThreadLocalRandom.current().nextInt(3000, 9000), 1 + ThreadLocalRandom.current()
                .nextInt(12), 1);
    }

    UUID holerite() {
        return holerite(competenciaLivre());
    }

    UUID holerite(LocalDate competencia) {
        return jdbc.sql("""
                        INSERT INTO fluxo.holerites (competencia, origem, hash, total_vencimentos, total_descontos,
                                                     liquido, data_liquido)
                        VALUES (:comp, 'MANUAL', :hash, 5000, 1200, 3800, :data) RETURNING id""")
                .param("comp", competencia).param("hash", UUID.randomUUID().toString()).param("data", HOJE)
                .query(UUID.class).single();
    }

    UUID gasto(String forma, UUID conta, UUID holerite, String valor) {
        return jdbc.sql("""
                        INSERT INTO fluxo.gastos (id, data, descricao, valor, categoria_id, forma, conta_id, holerite_id)
                        VALUES (:id, :data, 'Mercado', CAST(:valor AS NUMERIC), :categoria, :forma, :conta, :holerite)
                        RETURNING id""")
                .param("id", UUID.randomUUID()).param("data", HOJE).param("valor", valor)
                .param("categoria", categoria()).param("forma", forma).param("conta", conta)
                .param("holerite", holerite)
                .query(UUID.class).single();
    }

    UUID ganho(String tipo, String bruto, String liquido, UUID holerite) {
        return ganho(tipo, bruto, liquido, holerite, LocalDate.of(2026, 11, 1));
    }

    UUID ganho(String tipo, String bruto, String liquido, UUID holerite, LocalDate competencia) {
        return jdbc.sql("""
                        INSERT INTO fluxo.ganhos (id, competencia, descricao, tipo, bruto, liquido, holerite_id)
                        VALUES (:id, :comp, 'Freela', :tipo, CAST(:bruto AS NUMERIC), CAST(:liquido AS NUMERIC),
                                :holerite) RETURNING id""")
                .param("id", UUID.randomUUID()).param("comp", competencia).param("tipo", tipo)
                .param("bruto", bruto).param("liquido", liquido).param("holerite", holerite)
                .query(UUID.class).single();
    }
}
