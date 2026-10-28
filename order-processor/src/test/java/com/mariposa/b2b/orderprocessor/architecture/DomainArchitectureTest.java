package com.mariposa.b2b.orderprocessor.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * domain/ debe ser Java puro (propuesta §4.1 "Reglas"): nada de
 * Spring, Kafka, Mongo ni Jackson. Se verifica importando todo el paquete
 * base del proyecto, no solo domain, para que ArchUnit pueda resolver las
 * dependencias reales entre clases.
 */
class DomainArchitectureTest {

    private static final String DOMAIN_PACKAGE = "com.mariposa.b2b.orderprocessor.domain..";

    private static final String[] FORBIDDEN_PACKAGES = {
            "org.springframework..",
            "org.apache.kafka..",
            "com.mongodb..",
            "com.fasterxml.jackson.."
    };

    @Test
    void domainDoesNotDependOnSpringKafkaMongoOrJackson() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mariposa.b2b.orderprocessor");

        ArchRule rule = noClasses()
                .that().resideInAPackage(DOMAIN_PACKAGE)
                .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN_PACKAGES);

        rule.check(classes);
    }
}
