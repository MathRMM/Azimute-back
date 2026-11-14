package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;

/**
 * O que se deve no crédito, para a posição geral (RF32, RN12). Definida aqui e implementada por {@code cartoes}
 * (fase 5 do roadmap), para a seta de dependência continuar apontando para {@code contas}.
 * <p>
 * Dívida de crédito é qualquer compra no cartão ainda não paga, pelo valor total da compra (P6). Sem nenhuma
 * implementação, a dívida é zero.
 */
public interface FontesDeDivida {

    /** Total devido, positivo. */
    Dinheiro totalDevido();
}
