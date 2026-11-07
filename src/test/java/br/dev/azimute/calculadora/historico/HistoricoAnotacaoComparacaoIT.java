package br.dev.azimute.calculadora.historico;

import br.dev.azimute.TestcontainersConfig;
import br.dev.azimute.calculadora.core.JurosCompostos;
import br.dev.azimute.calculadora.core.TaxaMensal;
import br.dev.azimute.comum.Dinheiro;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Anotação, exclusão e comparação de propostas.
 * Refs: RF22, RF23, RF24, RN12, D3, D4, T3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class HistoricoAnotacaoComparacaoIT {

    private static final String LISTA = "/api/calculadora/historico";
    private static final String BASE = LISTA + "/";
    private static final String COMPARAR = LISTA + "/comparar";
    private static final String MOTO = "Honda CB 300F";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @BeforeEach
    void limpar() {
        jdbc.sql("TRUNCATE calculadora.simulacoes").update();
    }

    /**
     * Proposta coerente gerada pelo core: passa pela conferência do snapshot. A taxa informada é a "real";
     * a gravada é a implícita na parcela em centavos, como o app faria.
     */
    static Map<String, Object> proposta(String bem, String instituicao, String data,
                                        String valorBem, String taxaPercentual, int prazo) {
        var financiado = Dinheiro.de(valorBem);
        var parcela = JurosCompostos.parcela(financiado, TaxaMensal.dePercentual(taxaPercentual), prazo);
        var taxa = JurosCompostos.taxaImplicita(financiado, parcela, prazo);
        var totalPago = parcela.vezes(prazo);

        var corpo = HistoricoGravacaoIT.proposta();
        corpo.put("bem", bem);
        corpo.put("instituicao", instituicao);
        corpo.put("data", data);
        corpo.put("valorBem", valorBem);
        corpo.put("entrada", "0.00");
        corpo.put("parcela", parcela.toString());
        corpo.put("prazo", prazo);
        corpo.put("valorFinanciado", financiado.toString());
        corpo.put("taxaMensal", taxa.fracao().setScale(8, RoundingMode.HALF_EVEN).toPlainString());
        corpo.put("taxaAnual", taxa.anualizada().fracao().setScale(8, RoundingMode.HALF_EVEN).toPlainString());
        corpo.put("totalPago", totalPago.toString());
        corpo.put("totalJuros", totalPago.menos(financiado).toString());
        return corpo;
    }

    UUID salvar(Map<String, Object> corpo) throws Exception {
        var id = UUID.randomUUID();
        mvc.perform(put(BASE + id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo)))
                .andExpect(status().isOk());
        return id;
    }

    UUID salvarMotoReal() throws Exception {
        return salvar(HistoricoGravacaoIT.proposta());
    }

    ResultActions anotar(UUID id, String corpo) throws Exception {
        return mvc.perform(patch(BASE + id).contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    // ---- PATCH (RF23) ----

    @Test
    void anota_observacao_e_status() throws Exception {
        var id = salvarMotoReal();

        anotar(id, "{\"observacao\":\"vendedor ofereceu brinde\",\"status\":\"ACEITA\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observacao").value("vendedor ofereceu brinde"))
                .andExpect(jsonPath("$.status").value("ACEITA"))
                .andExpect(jsonPath("$.parcela").value("860.69"));

        mvc.perform(get(LISTA))
                .andExpect(jsonPath("$[0].observacao").value("vendedor ofereceu brinde"))
                .andExpect(jsonPath("$[0].status").value("ACEITA"));
    }

    @Test
    void campo_ausente_no_patch_nao_muda() throws Exception {
        var id = salvarMotoReal();
        anotar(id, "{\"observacao\":\"primeira\"}").andExpect(status().isOk());

        anotar(id, "{\"status\":\"DESCARTADA\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observacao").value("primeira"))
                .andExpect(jsonPath("$.status").value("DESCARTADA"));
    }

    @Test
    void observacao_nula_apaga_a_anotacao() throws Exception {
        var id = salvarMotoReal();
        anotar(id, "{\"observacao\":\"temporária\"}").andExpect(status().isOk());

        anotar(id, "{\"observacao\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observacao").doesNotExist());
    }

    @Test
    void patch_com_campo_de_valor_e_rejeitado_e_nada_muda() throws Exception {
        var id = salvarMotoReal();

        anotar(id, "{\"observacao\":\"x\",\"parcela\":\"700.00\"}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("CAMPO_NAO_EDITAVEL"))
                .andExpect(jsonPath("$.detalhes.campos[0]").value("parcela"));

        mvc.perform(get(LISTA))
                .andExpect(jsonPath("$[0].parcela").value("860.69"))
                .andExpect(jsonPath("$[0].observacao").doesNotExist());
    }

    @Test
    void status_fora_do_dominio_e_rejeitado() throws Exception {
        var id = salvarMotoReal();
        anotar(id, "{\"status\":\"FECHADA\"}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalhes.campos.status").exists());
        anotar(id, "{\"status\":null}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.status").exists());
    }

    @Test
    void patch_de_id_inexistente_e_404() throws Exception {
        anotar(UUID.randomUUID(), "{\"observacao\":\"x\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("SIMULACAO_NAO_ENCONTRADA"));
    }

    // ---- DELETE (RF24, D3, T3) ----

    @Test
    void exclui_proposta_sem_estimativas_e_repetir_tambem_e_204() throws Exception {
        var id = salvarMotoReal();

        mvc.perform(delete(BASE + id)).andExpect(status().isNoContent());
        mvc.perform(get(LISTA)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(delete(BASE + id)).andExpect(status().isNoContent());
    }

    @Test
    void proposta_ancora_nao_pode_ser_excluida() throws Exception {
        var ancora = salvarMotoReal();
        var e1 = salvar(HistoricoGravacaoIT.estimativa(ancora));
        var outra = HistoricoGravacaoIT.estimativa(ancora);
        outra.put("calculadaEm", "2026-09-12T16:00:00-03:00");
        var e2 = salvar(outra);

        mvc.perform(delete(BASE + ancora))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("SIMULACAO_E_ANCORA"))
                .andExpect(jsonPath("$.detalhes.estimativas", containsInAnyOrder(e1.toString(), e2.toString())));

        mvc.perform(delete(BASE + e1)).andExpect(status().isNoContent());
        mvc.perform(delete(BASE + e2)).andExpect(status().isNoContent());
        mvc.perform(delete(BASE + ancora)).andExpect(status().isNoContent());
    }

    // ---- comparação (RF22, D4) ----

    @Test
    void menor_taxa_e_a_melhor_e_a_economia_e_a_diferenca_de_juros() throws Exception {
        var montadora = proposta(MOTO, "Financeira da montadora", "2026-09-14", "17430.00", "3.20", 36);
        var bancoY = proposta(MOTO, "Banco Y", "2026-09-12", "17430.00", "4.14", 36);
        var idMontadora = salvar(montadora);
        salvar(bancoY);

        var juros = Dinheiro.de((String) bancoY.get("totalJuros")).menos(Dinheiro.de((String) montadora.get("totalJuros")));

        mvc.perform(get(COMPARAR).param("bem", MOTO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bem").value(MOTO))
                .andExpect(jsonPath("$.propostas", hasSize(2)))
                .andExpect(jsonPath("$.propostas[0].id").value(idMontadora.toString()))
                .andExpect(jsonPath("$.propostas[0].instituicao").value("Financeira da montadora"))
                .andExpect(jsonPath("$.propostas[0].melhor").value(true))
                .andExpect(jsonPath("$.propostas[0].prazo").value(36))
                .andExpect(jsonPath("$.propostas[0].valorFinanciado").value("17430.00"))
                .andExpect(jsonPath("$.propostas[1].melhor").value(false))
                .andExpect(jsonPath("$.economiaEntreMelhorEPior").value(juros.toString()));
    }

    @Test
    void estimativa_nao_entra_na_comparacao_por_padrao() throws Exception {
        var ancora = salvarMotoReal(); // 3,51% a.m.
        salvar(HistoricoGravacaoIT.estimativa(ancora));

        mvc.perform(get(COMPARAR).param("bem", MOTO))
                .andExpect(jsonPath("$.propostas", hasSize(1)))
                .andExpect(jsonPath("$.propostas[0].id").value(ancora.toString()));

        mvc.perform(get(COMPARAR).param("bem", MOTO).param("incluirEstimativas", "true"))
                .andExpect(jsonPath("$.propostas", hasSize(2)));
    }

    @Test
    void estimativa_nunca_vira_melhor_por_padrao_mesmo_com_juros_menores() throws Exception {
        // a estimativa tem a mesma taxa da âncora e juros menores (entrada maior); por padrão, nem aparece
        var ancora = salvarMotoReal();
        salvar(HistoricoGravacaoIT.estimativa(ancora));
        salvar(proposta(MOTO, "Banco Z", "2026-09-10", "17430.00", "4.50", 36));

        mvc.perform(get(COMPARAR).param("bem", MOTO))
                .andExpect(jsonPath("$.propostas", hasSize(2)))
                .andExpect(jsonPath("$.propostas[0].id").value(ancora.toString()))
                .andExpect(jsonPath("$.propostas[0].melhor").value(true));
    }

    @Test
    void uma_proposta_so_e_a_melhor_e_a_economia_e_zero() throws Exception {
        salvarMotoReal();
        mvc.perform(get(COMPARAR).param("bem", MOTO))
                .andExpect(jsonPath("$.propostas", hasSize(1)))
                .andExpect(jsonPath("$.propostas[0].melhor").value(true))
                .andExpect(jsonPath("$.economiaEntreMelhorEPior").value("0.00"));
    }

    @Test
    void bem_sem_propostas_devolve_lista_vazia() throws Exception {
        mvc.perform(get(COMPARAR).param("bem", "Bicicleta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bem").value("Bicicleta"))
                .andExpect(jsonPath("$.propostas", hasSize(0)))
                .andExpect(jsonPath("$.economiaEntreMelhorEPior").value("0.00"));
    }

    @Test
    void empate_de_taxa_favorece_a_mais_recente() throws Exception {
        salvar(proposta(MOTO, "Antiga", "2026-09-01", "17430.00", "3.51", 36));
        var recente = salvar(proposta(MOTO, "Recente", "2026-09-20", "17430.00", "3.51", 36));

        mvc.perform(get(COMPARAR).param("bem", MOTO))
                .andExpect(jsonPath("$.propostas[0].id").value(recente.toString()))
                .andExpect(jsonPath("$.propostas[0].melhor").value(true))
                .andExpect(jsonPath("$.propostas[1].melhor").value(false));
    }

    @Test
    void comparacao_agrupa_pelo_bem_ignorando_maiusculas() throws Exception {
        salvarMotoReal();
        salvar(proposta("Yamaha Fazer 250", "Banco Y", "2026-09-12", "20000.00", "2.00", 24));

        mvc.perform(get(COMPARAR).param("bem", " honda cb 300f"))
                .andExpect(jsonPath("$.bem").value(MOTO))
                .andExpect(jsonPath("$.propostas", hasSize(1)));
    }

    @Test
    void comparar_sem_bem_e_invalido() throws Exception {
        mvc.perform(get(COMPARAR))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("DADOS_INVALIDOS"))
                .andExpect(jsonPath("$.detalhes.campos.bem").exists());
        mvc.perform(get(COMPARAR).param("bem", "  "))
                .andExpect(status().is(422));
    }

    @Test
    void comparacao_nao_altera_nada() throws Exception {
        salvarMotoReal();
        var antes = jdbc.sql("SELECT count(*) FROM calculadora.simulacoes").query(Long.class).single();
        mvc.perform(get(COMPARAR).param("bem", MOTO)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM calculadora.simulacoes").query(Long.class).single()).isEqualTo(antes);
    }
}
