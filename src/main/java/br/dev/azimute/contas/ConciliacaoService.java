package br.dev.azimute.contas;

import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.comum.Fuso;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Conciliação com o extrato (RF20, RF21): o saldo real vira a nova base e a divergência é registrada em vez de
 * apagada. A divergência gravada nunca é recalculada (RN07).
 */
@Service
class ConciliacaoService {


    /** {@code criada} distingue a conciliação nova (201) do reenvio idêntico (200, T4). */
    record Resultado(Conciliacao conciliacao, boolean criada) {}

    private final ContaRepository contas;
    private final ConciliacaoRepository conciliacoes;
    private final ContaService saldos;
    private final Clock relogio;

    ConciliacaoService(ContaRepository contas, ConciliacaoRepository conciliacoes, ContaService saldos,
                       Clock relogio) {
        this.contas = contas;
        this.conciliacoes = conciliacoes;
        this.saldos = saldos;
        this.relogio = relogio;
    }

    /**
     * O reenvio idêntico da última conciliação (mesma data, mesmo saldo real) devolve a existente antes de
     * qualquer outra regra, como no reenvio de movimentação: a fila offline não recebe erro por repetir.
     */
    Resultado conciliar(ContaId id, LocalDate data, Dinheiro saldoReal, String justificativa) {
        var conta = contas.buscar(id).orElseThrow(() -> new ContaNaoEncontradaException(id));
        var ultima = conciliacoes.ultima(id).orElseThrow();
        if (reenvio(ultima, data, saldoReal)) {
            return new Resultado(ultima, false);
        }
        if (!conta.ativa()) {
            throw new ContaInativaException(id);
        }
        var hoje = Fuso.hoje(relogio);
        if (data.isAfter(hoje)) {
            throw new DataFuturaException(data, hoje);
        }
        if (!data.isAfter(ultima.data())) {
            throw new ConciliacaoForaDeOrdemException(ultima.data());
        }

        var calculado = saldos.saldoEm(id, data);
        var divergencia = saldoReal.menos(calculado);
        var texto = justificativa == null || justificativa.isBlank() ? null : justificativa.strip();
        try {
            var novaId = conciliacoes.inserir(id, data, saldoReal, calculado, divergencia, texto);
            return new Resultado(new Conciliacao(novaId, id, data, saldoReal, calculado, divergencia, texto), true);
        } catch (DuplicateKeyException e) {
            // Outro envio do mesmo dia gravou entre a leitura e a inserção.
            var vencedora = conciliacoes.ultima(id).orElseThrow();
            if (reenvio(vencedora, data, saldoReal)) {
                return new Resultado(vencedora, false);
            }
            throw new ConciliacaoForaDeOrdemException(vencedora.data());
        }
    }

    /** Da mais recente para a mais antiga, incluindo a conciliação inicial (RF21). */
    List<Conciliacao> historico(ContaId id) {
        contas.buscar(id).orElseThrow(() -> new ContaNaoEncontradaException(id));
        return conciliacoes.historico(id);
    }

    private static boolean reenvio(Conciliacao existente, LocalDate data, Dinheiro saldoReal) {
        return existente.data().equals(data) && existente.saldoReal().equals(saldoReal);
    }
}
