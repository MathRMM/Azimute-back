/**
 * A escrita nas tabelas do {@code fluxo} que mais de um pacote do módulo grava: gastos, ganhos e recebimentos. Cada
 * tabela tem um dono só, com a lista de colunas e a exclusão lógica num lugar; o que é de cada caso (leituras,
 * restauração, reaplicação do holerite) fica no repositório que usa.
 * <p>
 * Subpacote interno: o Spring Modulith não o expõe a outros módulos, mesmo com classes públicas. As classes são
 * públicas só para o subpacote {@code folha} usar.
 */
package br.dev.azimute.fluxo.tabelas;
