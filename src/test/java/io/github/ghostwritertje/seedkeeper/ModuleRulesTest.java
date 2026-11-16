package io.github.ghostwritertje.seedkeeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
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

    /**
     * The modules rules 2 and 3 already apply to. Temporary: each migration ticket adds the module it brings within
     * the rules, and the contract ticket deletes this list so that the rules apply to every module.
     */
    private static final Set<String> MIGRATED_MODULES =
            Set.of("auth", "groups", "history", "orphans", "settings", "system");

    private static final ModuleRules RULES = new ModuleRules(PRODUCTION, DOMAIN_MODULES, MIGRATED_MODULES);

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(PRODUCTION);

    @Test
    void modulesHaveNoCycles() {
        RULES.noCycles().check(PRODUCTION_CLASSES);
    }

    @Test
    void migratedModulesAreUsedOnlyThroughTheirModuleApi() {
        RULES.onlyApiAcrossModules().check(PRODUCTION_CLASSES);
    }

    @Test
    void migratedModulesKeepToTheModuleApiBudget() {
        RULES.apiBudget().check(PRODUCTION_CLASSES);
    }

    @Test
    void domainModulesAreFreeOfFrameworks() {
        RULES.domainIsPure().check(PRODUCTION_CLASSES);
    }

    @Test
    void rule4ReportsADomainModuleThatDependsOnQuarkus() {
        String root = FIXTURES + "domain";
        EvaluationResult result =
                new ModuleRules(root, Set.of("decide"), Set.of()).domainIsPure().evaluate(fixture(root));

        assertViolation(result, "Rule 4", "module decide", "Impure", "io.quarkus.runtime.LaunchMode");
        assertFalse(result.getFailureReport().toString().contains("Endpoint"), "web isn't a domain module");
    }

    @Test
    void rule2ReportsATypeUsedFromAnotherModuleThatIsNotModuleApi() {
        String root = FIXTURES + "api";
        EvaluationResult result = new ModuleRules(root, Set.of(), Set.of("lib"))
                .onlyApiAcrossModules()
                .evaluate(fixture(root));

        assertViolation(result, "Rule 2", "module lib", "lib.Hidden", "lib.Loose", "module app", "app.Reader");
        List<String> violations = result.getFailureReport().getDetails();
        assertEquals(
                2,
                violations.stream().map(ModuleRulesTest::usedType).distinct().count(),
                () -> "only Hidden and Loose break rule 2:\n" + String.join("\n", violations));
    }

    /** The type a rule 2 violation is about: the first one it names. */
    private static String usedType(String violation) {
        return violation.substring(violation.indexOf('<') + 1, violation.indexOf('>'));
    }

    @Test
    void rule3ReportsAModuleWithMoreThan3ModuleApiBehaviourTypes() {
        String root = FIXTURES + "budget";
        EvaluationResult result = new ModuleRules(root, Set.of(), Set.of("wide", "narrow"))
                .apiBudget()
                .evaluate(fixture(root));

        assertViolation(
                result, "Rule 3", "module wide", "has 4", "Wide$First", "Wide$Second", "Wide$Third", "Wide$Fourth");
        String report = result.getFailureReport().toString();
        assertFalse(report.contains("module narrow"), "narrow is within its budget:\n" + report);
        assertFalse(
                report.contains("Wide$Data") || report.contains("Wide$Kind"),
                "records and enums don't count:\n" + report);
    }

    @Test
    void rule1ReportsACycleBetweenModules() {
        String root = FIXTURES + "cycle";
        EvaluationResult result =
                new ModuleRules(root, Set.of(), Set.of()).noCycles().evaluate(fixture(root));

        assertViolation(result, "Rule 1", "module a", "module b", "Alpha", "Beta");
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
