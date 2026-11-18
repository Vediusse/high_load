package ru.itmo.highload.notification;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "ru.itmo.highload.notification",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CleanArchitectureTest {
    @ArchTest
    static final ArchRule domainIsIndependent =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "org.hibernate..",
                            "java.sql..",
                            "javax.sql..",
                            "feign..",
                            "com.fasterxml.jackson..",
                            "..config..",
                            "..configuration..",
                            "jakarta..",
                            "org.apache.kafka..",
                            "reactor..",
                            "..application..",
                            "..adapters..",
                            "java.io..",
                            "java.nio.file..");

    @ArchTest
    static final ArchRule useCasesDependOnPorts =
            noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "..adapters..",
                            "..configuration..",
                            "..config..",
                            "org.hibernate..",
                            "jakarta.persistence..",
                            "java.sql..",
                            "javax.sql..",
                            "org.apache.kafka..",
                            "feign..",
                            "com.fasterxml.jackson..",
                            "io.swagger..",
                            "java.io..",
                            "java.nio.file..",
                            "..common.web..",
                            "..common.error..",
                            "..common.security..",
                            "..common.events..");

    @ArchTest
    static final ArchRule inboundAdaptersDependOnPorts =
            noClasses()
                    .that()
                    .resideInAPackage("..adapters.in..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..adapters.out..");
}
