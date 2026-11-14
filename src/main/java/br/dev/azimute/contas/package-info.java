/**
 * Contas reais onde o dinheiro fica (corrente, poupança, investimento, carteira, benefício), as movimentações
 * entre elas e a conciliação com o extrato (06-modulo-contas, PRD de contas).
 * <p>
 * Módulo fundacional: {@code fluxo}, {@code cartoes} e {@code reserva} dependem dele, nunca o contrário.
 * Só os tipos públicos deste pacote formam a API do módulo.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Contas")
package br.dev.azimute.contas;
