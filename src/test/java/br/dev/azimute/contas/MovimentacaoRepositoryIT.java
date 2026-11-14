package br.dev.azimute.contas;

import br.dev.azimute.TestcontainersConfig;
import br.dev.azimute.comum.Competencia;
import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ida e volta no banco para os cinco tipos (RF14): o que é gravado volta igual, e a resposta da API leva os
 * campos de cada tipo. Os tipos que só entram pela fachada (Fase 5) também passam pelo mesmo mapeamento.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class MovimentacaoRepositoryIT {

    private static final LocalDate DATA = LocalDate.of(2026, 9, 5);

    @Autowired JdbcClient jdbc;
    @Autowired MovimentacaoRepository repositorio;

    @ParameterizedTest
    @ValueSource(strings = {"ENTRADA", "SAIDA", "TRANSFERENCIA", "PAGAMENTO_FATURA", "RENDIMENTO"})
    void cada_tipo_volta_do_banco_igual_ao_que_foi_gravado(String tipo) {
        var montador = new MontadorDeConta(jdbc);
        var nubank = new ContaId(montador.corrente());
        var cdb = new ContaId(new MontadorDeConta(jdbc).investimento());
        var id = new MovimentacaoId(UUID.randomUUID());
        var valor = Dinheiro.de("123.45");
        Movimentacao movimentacao = switch (tipo) {
            case "ENTRADA" -> new Movimentacao.Entrada(id, nubank, UUID.randomUUID(), valor, DATA);
            case "SAIDA" -> new Movimentacao.Saida(id, nubank, UUID.randomUUID(), valor, DATA);
            case "TRANSFERENCIA" -> new Movimentacao.Transferencia(id, nubank, cdb, valor, DATA, "reserva");
            case "PAGAMENTO_FATURA" -> new Movimentacao.PagamentoFatura(id, nubank, UUID.randomUUID(),
                    Competencia.de("2026-08"), valor, DATA);
            default -> new Movimentacao.Rendimento(id, cdb, valor, DATA);
        };

        assertThat(repositorio.inserirSeAusente(movimentacao)).isTrue();
        assertThat(repositorio.inserirSeAusente(movimentacao)).isFalse();

        var lida = repositorio.buscar(id).orElseThrow();
        assertThat(lida.movimentacao()).isEqualTo(movimentacao);
        assertThat(lida.excluida()).isFalse();

        var resposta = MovimentacaoResponse.de(lida.movimentacao());
        assertThat(resposta.tipo()).isEqualTo(tipo);
        assertThat(resposta.valor()).isEqualTo(valor);
        var conta = resposta.origem() != null ? resposta.origem() : resposta.destino();
        assertThat(conta).isIn(nubank.valor(), cdb.valor());
    }
}
