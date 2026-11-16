plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "br.dev"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"
extra["jqwikVersion"] = "1.9.3"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
    // Leitura do holerite em PDF por extração de texto (RNF03, Fase 6 do SPEC do fluxo)
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("net.jqwik:jqwik:${property("jqwikVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

tasks.withType<JavaCompile> {
    // jqwik e Spring usam nomes de parâmetros
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
    systemProperty("user.timezone", "America/Sao_Paulo")
    finalizedBy(tasks.jacocoTestReport, "relatorioParcial")
}

// Só o ponto de entrada fica fora: é um main de três linhas que nenhum teste chama.
val classesMedidas = sourceSets.main.get().output.classesDirs.asFileTree.matching {
    exclude("br/dev/azimute/AzimuteApplication*")
}

// Execução com --tests (make test-unit, make test-integracao, IDE) mede só uma parte do código.
// O relatório dela vai para jacoco/parcial e nunca sobrescreve o da suíte completa (jacoco/test).
val execucaoParcial = gradle.startParameter.taskRequests.flatMap { it.args }.any { it.startsWith("--tests") }

tasks.jacocoTestReport {
    onlyIf("só a suíte completa gera o relatório principal") { !execucaoParcial }
    classDirectories.setFrom(classesMedidas)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.register<JacocoReport>("relatorioParcial") {
    description = "Cobertura de uma execução filtrada por --tests (não vale para o mínimo de 90%)."
    onlyIf("só execuções filtradas geram o relatório parcial") { execucaoParcial }
    executionData(layout.buildDirectory.file("jacoco/test.exec"))
    sourceSets(sourceSets.main.get())
    classDirectories.setFrom(classesMedidas)
    reports {
        xml.required = false
        html.required = true
        html.outputLocation = layout.buildDirectory.dir("reports/jacoco/parcial/html")
    }
}

// Unitários + integração juntos; abaixo de 90% de linhas ou de ramos, o build falha.
tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(classesMedidas)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
