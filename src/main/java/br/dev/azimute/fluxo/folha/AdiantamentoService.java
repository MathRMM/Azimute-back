package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.contas.ContasFacade;
import br.dev.azimute.fluxo.tabelas.RecebimentoId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * RF34: o adiantamento lançado antes do holerite, na conta do salário. Ao importar o holerite da competência, ele é
 * associado ao ganho sem lançar de novo; se o valor divergir, o holerite prevalece.
 */
@Service
class AdiantamentoService {

    /** O adiantamento e se este envio o criou (201) ou é um reenvio (200). */
    record Resultado(AdiantamentoRepository.Adiantamento adiantamento, boolean criado) {}

    private final AdiantamentoRepository adiantamentos;
    private final HoleriteRepository holerites;
    private final ConfiguracaoFolhaService configuracao;
    private final ContasFacade contas;

    AdiantamentoService(AdiantamentoRepository adiantamentos, HoleriteRepository holerites,
                        ConfiguracaoFolhaService configuracao, ContasFacade contas) {
        this.adiantamentos = adiantamentos;
        this.holerites = holerites;
        this.configuracao = configuracao;
        this.contas = contas;
    }

    /** Idempotente pelo ID do cliente. Sem data, vale a padrão da RN18. */
    @Transactional
    Resultado registrar(RecebimentoId id, Competencia competencia, Dinheiro valor, LocalDate data) {
        var conta = configuracao.exigir().salario();
        var novo = new AdiantamentoRepository.Adiantamento(id, competencia, "ADIANTAMENTO", valor,
                DatasDoSalario.adiantamentoOu(data, competencia), conta, true);
        var existente = adiantamentos.buscar(id);
        if (existente.isPresent()) {
            if (!existente.get().equals(novo)) {
                throw new AdiantamentoDivergenteException(id);
            }
            return new Resultado(existente.get(), false);
        }
        if (holerites.existeVivo(competencia)) {
            throw new CompetenciaComHoleriteException(competencia);
        }
        if (adiantamentos.existeAntecipado(competencia)) {
            throw new AdiantamentoJaLancadoException(competencia);
        }
        contas.registrarEntrada(conta, id.valor(), valor, novo.data());
        adiantamentos.inserir(novo);
        return new Resultado(novo, true);
    }
}
