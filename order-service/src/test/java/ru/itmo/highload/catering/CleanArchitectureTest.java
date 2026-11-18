package ru.itmo.highload.catering;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
@AnalyzeClasses(packages="ru.itmo.highload.catering", importOptions=ImportOption.DoNotIncludeTests.class)
class CleanArchitectureTest {
    @ArchTest static final ArchRule domainIsIndependent = noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..","jakarta..","org.apache.kafka..","reactor..","..application..","..adapters..","java.io..","java.nio.file..");
    @ArchTest static final ArchRule useCasesDependOnPorts = noClasses().that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..","..adapters..","..configuration..");
}
