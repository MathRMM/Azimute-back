package br.dev.azimute.fluxo;

import br.dev.azimute.ClienteDeTeste;
import br.dev.azimute.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Categorias de gasto pela API (RF01–RF04, RN05, RN06). */
@SpringBootTest
@Import(TestcontainersConfig.class)
class CategoriasIT {

    private static final String CATEGORIAS = "/api/categorias";

    @Autowired WebApplicationContext contexto;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    MockMvc mvc;

    @BeforeEach
    void preparar() {
        mvc = ClienteDeTeste.autenticado(contexto, jdbc);
    }

    @Test
    void criar_devolve_201_com_o_id_e_a_categoria_aparece_na_lista() throws Exception {
        var nome = "Pets " + UUID.randomUUID();

        criar("  " + nome + " ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.nome").value(nome))
                .andExpect(jsonPath("$.predefinida").value(false))
                .andExpect(jsonPath("$.ativa").value(true));

        listar(false).andExpect(jsonPath("$[*].nome", hasItem(nome)));
    }

    @Test
    void lista_traz_as_predefinidas_em_ordem_alfabetica() throws Exception {
        var predefinidas = List.of("Alimentação", "Moradia", "Transporte", "Saúde", "Educação", "Lazer",
                "Assinaturas", "Vestuário", "Outros");

        // Só as pré-definidas: as criadas por outros testes têm UUID no nome, e a collation do banco ignora hífens.
        var nomes = nomes(listar(false)).stream().filter(predefinidas::contains).toList();

        assertThat(nomes).containsExactly("Alimentação", "Assinaturas", "Educação", "Lazer", "Moradia", "Outros",
                "Saúde", "Transporte", "Vestuário");
    }

    @Test
    void nome_repetido_com_outra_caixa_e_409_e_em_branco_e_422() throws Exception {
        criar(" LAZER ").andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("NOME_DE_CATEGORIA_EM_USO"));
        criar("   ").andExpect(status().is(422)).andExpect(jsonPath("$.erro").value("DADOS_INVALIDOS"));
    }

    @Test
    void renomear_desativar_e_reativar() throws Exception {
        var id = idDe(criar("Viagem " + UUID.randomUUID()));
        var novoNome = "Viagens " + UUID.randomUUID();

        alterar(id, Map.of("nome", novoNome)).andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value(novoNome));

        alterar(id, Map.of("ativa", false)).andExpect(status().isOk()).andExpect(jsonPath("$.ativa").value(false));
        listar(false).andExpect(jsonPath("$[*].nome", not(hasItem(novoNome))));
        listar(true).andExpect(jsonPath("$[*].nome", hasItem(novoNome)));

        alterar(id, Map.of("ativa", true)).andExpect(jsonPath("$.ativa").value(true));
        listar(false).andExpect(jsonPath("$[*].nome", hasItem(novoNome)));
    }

    @Test
    void erros_do_patch() throws Exception {
        var id = idDe(criar("Hobby " + UUID.randomUUID()));

        alterar(id, Map.of("nome", "Lazer")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("NOME_DE_CATEGORIA_EM_USO"));
        alterar(id, Map.of("predefinida", true)).andExpect(status().is(422))
                .andExpect(jsonPath("$.erro").value("CAMPO_NAO_EDITAVEL"));
        alterar(id, Map.of("nome", " ", "ativa", "sim")).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.nome").value("deve ser texto não vazio"))
                .andExpect(jsonPath("$.detalhes.campos.ativa").value("deve ser true ou false"));
        alterar(id, Map.of("nome", 10)).andExpect(status().is(422))
                .andExpect(jsonPath("$.detalhes.campos.nome").value("deve ser texto não vazio"));
        alterar(UUID.randomUUID(), Map.of("ativa", false)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erro").value("CATEGORIA_NAO_ENCONTRADA"));
    }

    @Test
    void sem_token_e_401() throws Exception {
        ClienteDeTeste.anonimo(contexto).perform(get(CATEGORIAS)).andExpect(status().isUnauthorized());
    }

    private ResultActions criar(String nome) throws Exception {
        return mvc.perform(post(CATEGORIAS).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("nome", nome))));
    }

    private ResultActions alterar(UUID id, Map<String, Object> corpo) throws Exception {
        return mvc.perform(patch(CATEGORIAS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }

    private ResultActions listar(boolean incluirInativas) throws Exception {
        return mvc.perform(get(CATEGORIAS).param("incluirInativas", String.valueOf(incluirInativas)))
                .andExpect(status().isOk());
    }

    private UUID idDe(ResultActions resposta) throws Exception {
        var corpo = resposta.andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(corpo).get("id").asString());
    }

    private List<String> nomes(ResultActions resposta) throws Exception {
        var corpo = resposta.andReturn().getResponse().getContentAsString();
        var nomes = new ArrayList<String>();
        for (var categoria : json.readTree(corpo)) {
            nomes.add(categoria.get("nome").asString());
        }
        return nomes;
    }
}
