package br.dev.azimute.comum;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** As bases de erro que os módulos estendem e a conferência de campos do PATCH. */
class ErrosComunsTest {

    static final class DadosInvalidos extends ErroDeDadosInvalidos {
        DadosInvalidos(Map<String, Object> campos) {
            super(campos);
        }
    }

    static final class CampoNaoEditavel extends ErroDeCampoNaoEditavel {
        CampoNaoEditavel(List<String> campos) {
            super(campos, "Só nome pode ser alterado");
        }
    }

    @Test
    void dados_invalidos_tem_sempre_o_mesmo_codigo_e_formato() {
        var erro = new DadosInvalidos(Map.of("valor", "deve ser maior que zero"));

        assertThat(erro.codigo()).isEqualTo("DADOS_INVALIDOS");
        assertThat(erro.tipo()).isEqualTo(ErroDeNegocio.Tipo.VALIDACAO);
        assertThat(erro.getMessage()).isEqualTo("Há campos inválidos na requisição");
        assertThat(erro.detalhes()).isEqualTo(Map.of("campos", Map.of("valor", "deve ser maior que zero")));
    }

    @Test
    void campo_nao_editavel_tem_o_codigo_comum_e_a_mensagem_do_modulo() {
        var erro = new CampoNaoEditavel(List.of("tipo"));

        assertThat(erro.codigo()).isEqualTo("CAMPO_NAO_EDITAVEL");
        assertThat(erro.tipo()).isEqualTo(ErroDeNegocio.Tipo.VALIDACAO);
        assertThat(erro.getMessage()).isEqualTo("Só nome pode ser alterado");
        assertThat(erro.detalhes()).isEqualTo(Map.of("campos", List.of("tipo")));
    }

    @Test
    void campos_editaveis_nomeia_os_proibidos_em_ordem() {
        var editaveis = new CamposEditaveis("nome", "ativa");

        assertThatThrownBy(() -> editaveis.conferir(Map.of("tipo", 1, "nome", "x", "id", 2), CampoNaoEditavel::new))
                .isInstanceOfSatisfying(CampoNaoEditavel.class,
                        e -> assertThat(e.detalhes()).isEqualTo(Map.of("campos", List.of("id", "tipo"))));
    }

    @Test
    void campos_editaveis_aceita_corpo_so_com_editaveis_ou_vazio() {
        var editaveis = new CamposEditaveis("nome", "ativa");

        assertThatCode(() -> editaveis.conferir(Map.of("nome", "x", "ativa", true), CampoNaoEditavel::new))
                .doesNotThrowAnyException();
        assertThatCode(() -> editaveis.conferir(Map.of(), CampoNaoEditavel::new)).doesNotThrowAnyException();
        assertThat(editaveis.nomes()).containsExactlyInAnyOrder("nome", "ativa");
    }
}
