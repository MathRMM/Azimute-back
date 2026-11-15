package br.dev.azimute.fluxo.folha;

import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Regras de contrapartida da previdência com vigência (RF35a, RN19). Regra nova vale só dali em diante: a
 * contrapartida de cada holerite é gravada na importação e não é recalculada (T7).
 */
@Service
class ContrapartidaService {

    private final RegraContrapartidaRepository regras;

    ContrapartidaService(RegraContrapartidaRepository regras) {
        this.regras = regras;
    }

    List<RegraContrapartida> listar() {
        return regras.listar();
    }

    RegraContrapartida incluir(RegraContrapartida regra) {
        regras.inserir(regra);
        return regra;
    }

    /** Básica × percentual da regra vigente na competência do holerite. */
    Dinheiro contrapartida(Competencia competencia, Dinheiro basica) {
        return RegraContrapartida.vigente(regras.listar(), competencia).contrapartida(basica);
    }
}
