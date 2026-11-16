package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Resumo do mês (RF50). Exige token, como tudo sob {@code /api} (SEC01). */
@RestController
@RequestMapping("/api/fluxo")
class ResumoController {

    record CategoriaResponse(UUID categoria, String nome, Dinheiro total, Dinheiro mediaTresMeses) {}

    record ResumoResponse(Competencia competencia, Dinheiro totalGasto, Dinheiro recebido, Dinheiro sobra,
                          List<CategoriaResponse> categorias) {
        static ResumoResponse de(ResumoMes r) {
            return new ResumoResponse(r.competencia(), r.totalGasto(), r.recebido(), r.sobra(),
                    r.categorias().stream().map(c -> new CategoriaResponse(c.categoria().valor(), c.nome(),
                            c.total(), c.mediaTresMeses())).toList());
        }
    }

    private final ResumoService resumos;

    ResumoController(ResumoService resumos) {
        this.resumos = resumos;
    }

    @GetMapping("/resumo")
    ResumoResponse resumo(@RequestParam String competencia) {
        Competencia mes;
        try {
            mes = Competencia.de(competencia);
        } catch (IllegalArgumentException e) {
            throw new CompetenciaInvalidaException(competencia);
        }
        return ResumoResponse.de(resumos.resumoDoMes(mes));
    }
}
