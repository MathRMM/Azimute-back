/**
 * Módulo {@code fluxo}: ganhos, gastos, categorias e o holerite (03-modulo-fluxo, PRD do fluxo).
 * <p>
 * Todo efeito de dinheiro em conta passa pela fachada de {@code contas}, na mesma transação (RN04). O holerite fica
 * no subpacote {@code folha}, interno ao módulo (T1 do SPEC do fluxo). Só os tipos públicos deste pacote formam a
 * API do módulo.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Fluxo")
package br.dev.azimute.fluxo;
