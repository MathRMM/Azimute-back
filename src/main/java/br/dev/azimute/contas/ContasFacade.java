package br.dev.azimute.contas;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A API de {@code contas} para os outros módulos (RF30). {@code fluxo} lança entradas e saídas; {@code cartoes},
 * pagamentos de fatura; {@code reserva} pergunta quais contas são de reserva.
 * <p>
 * Ganho, gasto e cartão chegam como {@link UUID}: os tipos deles pertencem aos módulos de origem (T1).
 * Cada registro é idempotente pela referência: repetir o mesmo ganho, gasto ou fatura não lança de novo (T5).
 * Conta inexistente ou inativa é recusada (RN08).
 */
public interface ContasFacade {

    Dinheiro saldoAtual(ContaId conta);

    void registrarEntrada(ContaId destino, UUID ganhoId, Dinheiro valor, LocalDate data);

    void registrarSaida(ContaId origem, UUID gastoId, Dinheiro valor, LocalDate data);

    /** Liquidação de fatura: não é gasto, a compra já foi lançada (RN03). */
    void registrarPagamentoFatura(ContaId origem, UUID cartaoId, Competencia fatura, Dinheiro valor, LocalDate data);

    /** Só as contas ativas com função {@code RESERVA}, para a conciliação com as categorias de reserva. */
    List<Conta> contasDeReserva();

    /** A conta, ativa ou não; vazio quando não existe. O {@code fluxo} confere o tipo das contas da folha. */
    Optional<Conta> buscarConta(ContaId id);

    /**
     * Corrige a entrada ou saída viva lançada com essa referência: conta, valor e data mudam, o ID da movimentação
     * fica (RF42 e T2 do SPEC do fluxo). Referência sem lançamento vivo → {@code LANCAMENTO_NAO_ENCONTRADO};
     * conta nova inexistente ou inativa é recusada (RN08).
     */
    void alterarLancamento(UUID referencia, ContaId conta, Dinheiro valor, LocalDate data);

    /** Exclusão lógica pela referência. Inexistente ou já excluída não é erro. */
    void excluirLancamento(UUID referencia);

    /** Desfaz a exclusão pela referência; sem nenhum lançamento com ela → {@code LANCAMENTO_NAO_ENCONTRADO}. */
    void restaurarLancamento(UUID referencia);
}
