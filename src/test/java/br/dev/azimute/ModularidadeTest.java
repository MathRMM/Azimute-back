package br.dev.azimute;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fronteiras entre módulos verificadas no build (RF04).
 */
class ModularidadeTest {

    private final ApplicationModules modulos = ApplicationModules.of(AzimuteApplication.class);

    @Test
    void fronteiras_entre_modulos_sao_respeitadas() {
        modulos.verify();
    }

    @Test
    void comum_nao_depende_de_spring_nem_de_jackson() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("br.dev.azimute.comum");

        // package-info só carrega a anotação de metadado do Modulith
        noClasses().that().doNotHaveSimpleName("package-info")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "tools.jackson..", "com.fasterxml.jackson..")
                .because("comum é só tipo de valor, sem Spring e sem I/O (regra 1 da doc 00)")
                .check(classes);
    }

    @Test
    void core_da_calculadora_e_funcao_pura_sobre_comum() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("br.dev.azimute.calculadora.core");

        classes().that().doNotHaveSimpleName("package-info")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "br.dev.azimute.comum..", "br.dev.azimute.calculadora.core..")
                .because("o core é espelhado no app e extraível como biblioteca (regra 2 da doc 00)")
                .check(classes);
    }

    @Test
    void api_publica_de_contas_e_so_o_que_os_outros_modulos_usam() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("br.dev.azimute.contas");

        // O resto de contas é interno (RF31): um tipo público novo precisa ser uma decisão, não um descuido.
        assertThat(classes.stream()
                .filter(c -> c.isTopLevelClass() && c.getModifiers().contains(JavaModifier.PUBLIC))
                .map(JavaClass::getSimpleName))
                .containsExactlyInAnyOrder("Conta", "ContaId", "TipoConta", "Funcao", "ContasFacade",
                        "FontesDeDivida");
    }

    @Test
    void api_publica_de_fluxo_e_so_o_que_os_outros_modulos_usam() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("br.dev.azimute.fluxo");

        // Só o pacote base é API do módulo; o subpacote folha é interno para o Spring Modulith (T1 do SPEC do fluxo).
        // A lista cresce a cada fase do SPEC até FluxoFacade, ResumoMes, RegistrarGasto e os eventos.
        assertThat(classes.stream()
                .filter(c -> c.getPackageName().equals("br.dev.azimute.fluxo"))
                .filter(c -> c.isTopLevelClass() && c.getModifiers().contains(JavaModifier.PUBLIC))
                .map(JavaClass::getSimpleName))
                .containsExactlyInAnyOrder("GastoId", "GanhoId", "CategoriaId");
    }

    @Test
    void todos_os_modulos_do_roadmap_existem() {
        assertThat(modulos.stream().map(m -> m.getIdentifier().toString()))
                .contains("comum", "plataforma", "calculadora", "auth",
                        "contas", "fluxo", "cartoes", "reserva",
                        "treino", "saude", "sistema", "agenda", "integracao");
    }
}
