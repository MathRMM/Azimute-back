package br.dev.azimute.fluxo.folha;

import br.dev.azimute.contas.ContaId;
import br.dev.azimute.contas.ContasFacade;
import br.dev.azimute.contas.TipoConta;
import org.springframework.stereotype.Service;

/**
 * RF30: as contas para onde vão o salário, o FGTS e a previdência. O tipo é conferido pela fachada de
 * {@code contas} (T2): FGTS numa conta {@code FGTS}, previdência numa {@code PREVIDENCIA}, e o salário numa conta
 * disponível, nunca numa das duas bloqueadas.
 */
@Service
class ConfiguracaoFolhaService {

    private final ConfiguracaoFolhaRepository configuracoes;
    private final ContasFacade contas;

    ConfiguracaoFolhaService(ConfiguracaoFolhaRepository configuracoes, ContasFacade contas) {
        this.configuracoes = configuracoes;
        this.contas = contas;
    }

    ConfiguracaoFolha configurar(ConfiguracaoFolha configuracao) {
        if (tipo(configuracao.salario()).bloqueado()) {
            throw new TipoDeContaInvalidoException("contaSalario", "conta disponível (não FGTS nem PREVIDENCIA)");
        }
        exigirTipo(configuracao.fgts(), TipoConta.FGTS, "contaFgts");
        exigirTipo(configuracao.previdencia(), TipoConta.PREVIDENCIA, "contaPrevidencia");
        configuracoes.salvar(configuracao);
        return configuracao;
    }

    /** Sem configuração, a folha não lança nada (RF30). */
    ConfiguracaoFolha exigir() {
        return configuracoes.buscar().orElseThrow(FolhaNaoConfiguradaException::new);
    }

    private void exigirTipo(ContaId conta, TipoConta esperado, String campo) {
        if (tipo(conta) != esperado) {
            throw new TipoDeContaInvalidoException(campo, esperado.name());
        }
    }

    private TipoConta tipo(ContaId conta) {
        return contas.buscarConta(conta).orElseThrow(() -> new ContaDaFolhaNaoEncontradaException(conta)).tipo();
    }
}
