package io.github.ghostwritertje.seedkeeper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The deep-module rules (GLOSSARY.md, Module and Module API), without starting the app. Each rule runs against the
 * production classes, and against a fixture under {@code io.github.ghostwritertje.archfixtures} that breaks it once.
 */
class ModuleRulesTest {

    private static final String FIXTURES = "io.github.ghostwritertje.archfixtures.";

    private static final String PRODUCTION = "io.github.ghostwritertje.seedkeeper";

    /** The modules that must stay pure domain code: rule 4. */
    private static final Set<String> DOMAIN_MODULES = Set.of("decide", "snapshot");

    private static final ModuleRules RULES = new ModuleRules(PRODUCTION, DOMAIN_MODULES, Set.of());

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(PRODUCTION);

    @Test
    void modulesHaveNoCycles() {
        RULES.noCycles().check(PRODUCTION_CLASSES);
    }

    @Test
    void domainModulesAreFreeOfFrameworks() {
        RULES.domainIsPure().check(PRODUCTION_CLASSES);
    }

    @Test
    void rule4ReportsADomainModuleThatDependsOnQuarkus() {
        String root = FIXTURES + "domain";
        EvaluationResult result = new ModuleRules(root, Set.of("decide"), Set.of()).domainIsPure()
                .evaluate(fixture(root));

        assertViolation(result, "Rule 4", "module decide", "Impure", "io.quarkus.runtime.LaunchMode");
        assertFalse(result.getFailureReport().toString().contains("Endpoint"), "web isn't a domain module");
    }

    @Test
    void rule1ReportsACycleBetweenModules() {
        String root = FIXTURES + "cycle";
        EvaluationResult result = new ModuleRules(root, Set.of(), Set.of()).noCycles().evaluate(fixture(root));

        assertViolation(result, "Rule 1", "a", "b", "Alpha", "Beta");
    }

    private static JavaClasses fixture(String root) {
        return new ClassFileImporter().importPackages(root);
    }

    private static void assertViolation(EvaluationResult result, String... expected) {
        assertTrue(result.hasViolation(), "expected a violation, but the rule passed");
        String report = result.getFailureReport().toString();
        for (String part : expected) {
            assertTrue(report.contains(part), () -> "the report doesn't name " + part + ":\n" + report);
        }
    }
}
