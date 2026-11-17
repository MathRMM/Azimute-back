package br.dev.azimute.comum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Cada tipo do leitor de PATCH: ausente, nulo, formato errado e valor no limite. */
class LeitorDeCamposTest {

    enum Cor { AZUL, VERDE }

    record CorId(UUID valor) {}

    static final class DadosInvalidos extends ErroDeDadosInvalidos {
        DadosInvalidos(Map<String, Object> campos) {
            super(campos);
        }
    }

    private static LeitorDeCampos leitor(String campo, Object valor) {
        var corpo = new HashMap<String, Object>();
        corpo.put(campo, valor);
        return new LeitorDeCampos(corpo);
    }

    private static Map<String, Object> erros(LeitorDeCampos leitor) {
        try {
            leitor.conferir(DadosInvalidos::new);
            return Map.of();
        } catch (DadosInvalidos e) {
            @SuppressWarnings("unchecked")
            var campos = (Map<String, Object>) e.detalhes().get("campos");
            return campos;
        }
    }

    @Test
    void ausente_e_nulo_nao_mudam_em_nenhum_tipo() {
        for (var leitor : List.of(new LeitorDeCampos(Map.of()), leitor("x", null))) {
            assertThat(leitor.texto("x")).isNull();
            assertThat(leitor.textoNaoVazio("x")).isNull();
            assertThat(leitor.booleano("x")).isNull();
            assertThat(leitor.data("x")).isNull();
            assertThat(leitor.competencia("x")).isNull();
            assertThat(leitor.dinheiroPositivo("x")).isNull();
            assertThat(leitor.dinheiroNaoNegativo("x")).isNull();
            assertThat(leitor.id("x", CorId::new)).isNull();
            assertThat(leitor.enumeracao("x", Cor.class)).isNull();
            assertThatCode(() -> leitor.conferir(DadosInvalidos::new)).doesNotThrowAnyException();
        }
    }

    @Test
    void texto_aceita_vazio_e_recusa_o_que_nao_e_texto() {
        assertThat(leitor("x", "").texto("x")).isEmpty();
        var leitor = leitor("x", 10);
        assertThat(leitor.texto("x")).isNull();
        assertThat(erros(leitor)).isEqualTo(Map.of("x", "deve ser texto"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void texto_nao_vazio_recusa_em_branco(String valor) {
        var leitor = leitor("x", valor);
        assertThat(leitor.textoNaoVazio("x")).isNull();
        assertThat(erros(leitor)).isEqualTo(Map.of("x", "deve ser texto não vazio"));
        assertThat(leitor("x", " a ").textoNaoVazio("x")).isEqualTo(" a ");
    }

    @Test
    void booleano_so_aceita_true_ou_false() {
        assertThat(leitor("x", false).booleano("x")).isFalse();
        var leitor = leitor("x", "true");
        assertThat(leitor.booleano("x")).isNull();
        assertThat(erros(leitor)).isEqualTo(Map.of("x", "deve ser true ou false"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30", "16/11/2026", "2026-11"})
    void data_recusa_formato_errado(String valor) {
        var leitor = leitor("x", valor);
        assertThat(leitor.data("x")).isNull();
        assertThat(erros(leitor)).isEqualTo(Map.of("x", "deve estar no formato AAAA-MM-DD"));
    }

    @Test
    void data_no_limite_e_numero_no_lugar_de_texto() {
        assertThat(leitor("x", "2028-02-29").data("x")).isEqualTo(LocalDate.of(2028, 2, 29));
        var leitor = leitor("x", 20261116);
        assertThat(leitor.data("x")).isNull();
        assertThat(erros(leitor)).isEqualTo(Map.of("x", "deve estar no formato AAAA-MM-DD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13", "2026-1", "11/2026", "2026-11-01"})
    void competencia_recusa_formato_errado(String valor) {
        var leitor = leitor("x", valor);
        assertThat(leitor.competencia("x")).isNull();
        assertThat(erros(leitor)).isEqualTo(Map.of("x", "deve estar no formato AAAA-MM"));
    }

    @Test
    void competencia_valida() {
        assertThat(leitor("x", "2026-12").competencia("x")).isEqualTo(Competencia.de("2026-12"));
    }

    @Test
    void dinheiro_aceita_numero_ou_texto_com_ate_duas_casas() {
        assertThat(leitor("x", new BigDecimal("10.50")).dinheiroPositivo("x")).isEqualTo(Dinheiro.de("10.50"));
        assertThat(leitor("x", 10).dinheiroPositivo("x")).isEqualTo(Dinheiro.de("10"));
        assertThat(leitor("x", "0.01").dinheiroPositivo("x")).isEqualTo(Dinheiro.de("0.01"));
        assertThat(leitor("x", 0).dinheiroNaoNegativo("x")).isEqualTo(Dinheiro.ZERO);
    }

    @Test
    void dinheiro_positivo_recusa_zero_negativo_tres_casas_e_lixo() {
        for (Object valor : List.of(0, new BigDecimal("-0.01"), new BigDecimal("10.505"), "10.505", "abc", true)) {
            var leitor = leitor("x", valor);
            assertThat(leitor.dinheiroPositivo("x")).isNull();
            assertThat(erros(leitor)).isEqualTo(Map.of("x", "deve ser um valor positivo com até 2 casas decimais"));
        }
    }

    @Test
    void dinheiro_nao_negativo_com_a_mensagem_padrao_ou_a_do_modulo() {
        var padrao = leitor("x", "-1");
        assertThat(padrao.dinheiroNaoNegativo("x")).isNull();
        assertThat(erros(padrao)).isEqualTo(Map.of("x", "deve ser um valor não negativo com até 2 casas decimais"));

        var doModulo = leitor("x", "1.999");
        assertThat(doModulo.dinheiroNaoNegativo("x", "outra mensagem")).isNull();
        assertThat(erros(doModulo)).isEqualTo(Map.of("x", "outra mensagem"));
    }

    @Test
    void id_vem_sempre_como_texto_e_usa_a_fabrica_do_tipo() {
        var uuid = UUID.fromString("0190f3a0-0000-7000-8000-000000000001");
        assertThat(leitor("x", uuid.toString()).id("x", CorId::new)).isEqualTo(new CorId(uuid));

        for (Object valor : List.of("nao-e-uuid", 123, uuid)) {
            var leitor = leitor("x", valor);
            assertThat(leitor.id("x", CorId::new)).isNull();
            assertThat(erros(leitor)).isEqualTo(Map.of("x", "deve ser um ID válido"));
        }
    }

    @Test
    void enumeracao_com_a_mensagem_padrao_ou_a_do_modulo() {
        assertThat(leitor("x", "VERDE").enumeracao("x", Cor.class)).isEqualTo(Cor.VERDE);

        var padrao = leitor("x", "verde");
        assertThat(padrao.enumeracao("x", Cor.class)).isNull();
        assertThat(erros(padrao)).isEqualTo(Map.of("x", "deve ser um de [AZUL, VERDE]"));

        var doModulo = leitor("x", 1);
        assertThat(doModulo.enumeracao("x", Cor.class, "deve ser AZUL ou VERDE")).isNull();
        assertThat(erros(doModulo)).isEqualTo(Map.of("x", "deve ser AZUL ou VERDE"));
    }

    @Test
    void enumeracao_obrigatoria_recusa_ausente_com_a_mesma_mensagem() {
        var leitor = new LeitorDeCampos(Map.of());
        assertThat(leitor.enumeracaoObrigatoria("x", Cor.class)).isNull();
        assertThat(erros(leitor)).isEqualTo(Map.of("x", "deve ser um de [AZUL, VERDE]"));
    }

    @Test
    void acumula_os_erros_na_ordem_de_leitura_e_fica_o_primeiro_de_cada_campo() {
        var leitor = new LeitorDeCampos(Map.of("b", 1, "a", "x"));
        leitor.booleano("b");
        leitor.data("a");
        leitor.erro("b", "outro erro");

        assertThat(leitor.temErro("a")).isTrue();
        assertThat(leitor.temErro("c")).isFalse();
        var campos = erros(leitor);
        assertThat(campos.keySet()).containsExactly("b", "a");
        assertThat(campos).isEqualTo(Map.of("b", "deve ser true ou false", "a", "deve estar no formato AAAA-MM-DD"));
    }
}
