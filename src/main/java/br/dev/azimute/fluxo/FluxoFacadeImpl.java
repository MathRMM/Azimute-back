package br.dev.azimute.fluxo;

import br.dev.azimute.comum.Competencia;
import org.springframework.stereotype.Service;

@Service
class FluxoFacadeImpl implements FluxoFacade {

    private final ResumoService resumos;
    private final GastoService gastos;

    FluxoFacadeImpl(ResumoService resumos, GastoService gastos) {
        this.resumos = resumos;
        this.gastos = gastos;
    }

    @Override
    public ResumoMes resumoDoMes(Competencia competencia) {
        return resumos.resumoDoMes(competencia);
    }

    @Override
    public GastoId registrarGasto(RegistrarGasto comando) {
        gastos.registrar(comando.id(), new NovoGasto(comando.data(), comando.descricao(), comando.valor(),
                comando.categoria(), comando.conta()));
        return comando.id();
    }
}
