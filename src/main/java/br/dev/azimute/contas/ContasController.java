package br.dev.azimute.contas;

import br.dev.azimute.comum.CamposEditaveis;
import br.dev.azimute.comum.Dinheiro;
import br.dev.azimute.comum.LeitorDeCampos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contas e saldo (SPEC de contas §6.4). Exige token, como tudo sob {@code /api} (SEC01). */
@RestController
@RequestMapping("/api/contas")
class ContasController {

    private static final CamposEditaveis CAMPOS_EDITAVEIS =
            new CamposEditaveis("apelido", "instituicao", "funcao", "limiteChequeEspecial", "ativa");

    /** Visão central (RF06): os valores globais e o saldo de cada conta que os compõe. */
    record ConsolidadoResponse(Dinheiro posicaoGeral, Dinheiro dividas, Dinheiro saldoEmConta, Dinheiro bloqueado,
                               List<ContaResponse> contas) {}

    private final ContaService contas;
    private final ConsolidadoService consolidado;

    ContasController(ContaService contas, ConsolidadoService consolidado) {
        this.contas = contas;
        this.consolidado = consolidado;
    }

    @GetMapping("/consolidado")
    ConsolidadoResponse consolidado() {
        var c = consolidado.consolidado();
        return new ConsolidadoResponse(c.posicaoGeral(), c.dividas(), c.saldoEmConta(), c.bloqueado(),
                c.contas().stream().map(ContaResponse::de).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ContaResponse criar(@Valid @RequestBody CriarContaRequest corpo) {
        return ContaResponse.de(contas.criar(corpo.paraDominio()));
    }

    @GetMapping
    List<ContaResponse> listar(@RequestParam(defaultValue = "false") boolean incluirInativas) {
        return contas.listar(incluirInativas).stream().map(ContaResponse::de).toList();
    }

    /**
     * O corpo é lido como mapa para nomear qualquer campo proibido, inclusive {@code tipo} (RF04). Campo ausente
     * ou nulo não muda.
     */
    @PatchMapping("/{id}")
    ContaResponse alterar(@PathVariable UUID id, @RequestBody Map<String, Object> corpo) {
        CAMPOS_EDITAVEIS.conferir(corpo, CampoNaoEditavelException::new);
        return ContaResponse.de(contas.alterar(new ContaId(id), lerAlteracao(corpo)));
    }

    @GetMapping("/{id}/saldo")
    ContaResponse.SaldoResponse saldo(@PathVariable UUID id) {
        return ContaResponse.SaldoResponse.de(contas.saldo(new ContaId(id)));
    }

    /** O limite segue as regras do POST: não negativo e no máximo 2 casas, sem arredondar. */
    private static AlteracaoConta lerAlteracao(Map<String, Object> corpo) {
        var leitor = new LeitorDeCampos(corpo);
        var apelido = leitor.texto("apelido");
        var instituicao = leitor.texto("instituicao");
        var funcao = leitor.enumeracao("funcao", Funcao.class);
        var limite = leitor.dinheiroNaoNegativo("limiteChequeEspecial");
        var ativa = leitor.booleano("ativa");
        leitor.conferir(DadosInvalidosException::new);
        return new AlteracaoConta(apelido, instituicao, funcao, limite, ativa);
    }
}
