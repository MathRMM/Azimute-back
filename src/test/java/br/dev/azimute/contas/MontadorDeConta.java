package br.dev.azimute.contas;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Cria contas e conciliações direto no banco para os testes de integração. O apelido é aleatório por padrão,
 * porque o banco é compartilhado entre as classes de teste e o apelido é único (RN09).
 */
final class MontadorDeConta {

    private final JdbcClient jdbc;
    private String apelido = "Conta " + UUID.randomUUID();
    private String limite = "0";

    MontadorDeConta(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    MontadorDeConta comApelido(String apelido) {
        this.apelido = apelido;
        return this;
    }

    MontadorDeConta comLimite(String limite) {
        this.limite = limite;
        return this;
    }

    UUID corrente() {
        return inserir("CORRENTE", "GIRO");
    }

    UUID poupanca() {
        return inserir("POUPANCA", "RESERVA");
    }

    UUID investimento() {
        return inserir("INVESTIMENTO", "RESERVA");
    }

    UUID conciliar(UUID conta, LocalDate data, String saldoReal) {
        return jdbc.sql("""
                        INSERT INTO contas.conciliacoes (conta_id, data, saldo_real, saldo_calculado, divergencia)
                        VALUES (:conta, :data, CAST(:saldo AS NUMERIC), CAST(:saldo AS NUMERIC), 0) RETURNING id""")
                .param("conta", conta).param("data", data).param("saldo", saldoReal)
                .query(UUID.class).single();
    }

    private UUID inserir(String tipo, String funcao) {
        return jdbc.sql("""
                        INSERT INTO contas.contas (apelido, instituicao, tipo, funcao, limite_cheque_especial)
                        VALUES (:apelido, 'Banco', :tipo, :funcao, CAST(:limite AS NUMERIC)) RETURNING id""")
                .param("apelido", apelido).param("tipo", tipo).param("funcao", funcao).param("limite", limite)
                .query(UUID.class).single();
    }
}
