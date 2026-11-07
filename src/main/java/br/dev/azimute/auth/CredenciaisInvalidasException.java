package br.dev.azimute.auth;

import br.dev.azimute.comum.ErroDeNegocio;

/** Mesma resposta para login inexistente e senha errada: não revela se o login existe (RN18). */
final class CredenciaisInvalidasException extends ErroDeNegocio {

    CredenciaisInvalidasException() {
        super("CREDENCIAIS_INVALIDAS", "Login ou senha inválidos", Tipo.NAO_AUTENTICADO);
    }
}
