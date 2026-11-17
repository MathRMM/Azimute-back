package br.dev.azimute;

import br.dev.azimute.comum.Identificador;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * As convenções de valores e modelos (seção do mesmo nome na doc 00) verificadas no build. Quem fugir do padrão
 * recebe um teste falhando; cada exceção está na regra, com o motivo.
 */
class PadroesDoCodigoTest {

    private static final JavaClasses PRODUCAO = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("br.dev.azimute");

    @Test
    void todo_id_implementa_identificador() {
        classes().that().haveSimpleNameEndingWith("Id").and().areAssignableTo(Record.class)
                .should().implement(Identificador.class)
                .because("todo ID de entidade expõe o UUID do mesmo jeito (Identificador)")
                .check(PRODUCAO);
    }

    @Test
    void uuid_do_servidor_e_v7() {
        noClasses().that().doNotHaveSimpleName("DispositivoService")
                .should().callMethod(UUID.class, "randomUUID")
                .because("ID gerado no servidor é v7 (Uuid7.novo()); o DispositivoService só usa o v4 no hash "
                        + "fictício que iguala o tempo de resposta do login errado")
                .check(PRODUCAO);
    }

    @Test
    void sem_builders() {
        noClasses().should().haveSimpleNameEndingWith("Builder")
                .because("records validados no construtor e fábricas nomeadas; builder permite objeto pela metade")
                .check(PRODUCAO);
    }

    @Test
    void record_nao_tem_setter() {
        methods().that().areDeclaredInClassesThat().areAssignableTo(Record.class)
                .should().haveNameNotStartingWith("set")
                .because("record é imutável; a alteração cria um valor novo")
                .check(PRODUCAO);
    }

    @Test
    void big_decimal_so_onde_o_valor_ainda_nao_e_de_dominio() {
        fields().that(DescribedPredicate.describe("de record de domínio",
                        (JavaField campo) -> campo.getOwner().isAssignableTo(Record.class) && !excecao(campo)))
                .should().notHaveRawType(BigDecimal.class)
                .because("dinheiro é Dinheiro, taxa é Taxa e percentual é Percentual")
                .check(PRODUCAO);
    }

    /** Onde o {@code BigDecimal} cru é intencional. */
    private static boolean excecao(JavaField campo) {
        var dono = campo.getOwner();
        var nome = dono.getSimpleName();
        return nome.endsWith("Request") // entrada da API, antes de validar (vira Dinheiro/Taxa no controller)
                || dono.getPackageName().equals("br.dev.azimute.comum") // os próprios tipos de valor
                || dono.getPackageName().equals("br.dev.azimute.calculadora.core") // core espelhado no app
                // a referência da rubrica é quantidade (horas, dias, %), não dinheiro
                || (Set.of("RubricaLida", "RubricaResponse").contains(nome) && campo.getName().equals("referencia"))
                // snapshot como o cliente mandou: aceita negativa para acumular o erro com os outros campos
                || (nome.equals("DadosSimulacao") && Set.of("taxaMensal", "taxaAnual").contains(campo.getName()));
    }

    @Test
    void controller_usa_campos_editaveis() {
        fields().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .should().notHaveRawType(Set.class).andShould().notHaveRawType(Collection.class)
                .because("os campos de PATCH ficam num CamposEditaveis, não num Set<String> solto")
                .check(PRODUCAO);
    }
}
