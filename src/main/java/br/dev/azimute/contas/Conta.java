package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;

import java.util.Objects;

/**
 * Conta real do usuário. O limite de cheque especial só existe em conta corrente (RN11, T7); nas outras é zero.
 */
public record Conta(ContaId id, String apelido, String instituicao, TipoConta tipo, Funcao funcao,
                    Dinheiro limiteChequeEspecial, boolean ativa) {

    public Conta {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(apelido, "apelido");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(funcao, "funcao");
        Objects.requireNonNull(limiteChequeEspecial, "limiteChequeEspecial");
    }
}
