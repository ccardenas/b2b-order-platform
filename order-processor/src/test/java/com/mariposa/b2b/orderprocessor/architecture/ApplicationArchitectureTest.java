package com.mariposa.b2b.orderprocessor.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * application/ orquesta puertos y dominio: no conoce frameworks ni adaptadores
 * (propuesta §4.1). Slf4j si esta permitido: es una fachada de logging, no un framework.
 */
class ApplicationArchitectureTest {

    private static final String BASE = "com.mariposa.b2b.orderprocessor";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    void applicationDoesNotDependOnFrameworksOrInfrastructure() {
        noClasses().that().resideInAPackage(BASE + ".application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "org.apache.kafka..", "com.mongodb..", "com.fasterxml.jackson..",
                        "java.net.http..", BASE + ".infrastructure..")
                .check(classes);
    }

    @Test
    void domainDoesNotDependOnApplicationOrInfrastructure() {
        noClasses().that().resideInAPackage(BASE + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(BASE + ".application..", BASE + ".infrastructure..")
                .check(classes);
    }
}
