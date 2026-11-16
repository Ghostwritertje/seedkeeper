package io.github.ghostwritertje.seedkeeper;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.Set;

/**
 * The deep-module rules for the modules under one root package. A module is a top-level subpackage of the root,
 * together with its subpackages; the root package itself holds only app wiring and is no module.
 */
final class ModuleRules {

    /** What a domain module may not depend on: Quarkus, Jakarta (JAX-RS, CDI, …), JDBC and Jackson. */
    private static final String[] FRAMEWORKS = {
        "io.quarkus..", "jakarta..", "javax.ws.rs..", "java.sql..", "javax.sql..", "com.fasterxml.jackson.."
    };

    private final String root;
    private final Set<String> domainModules;
    private final Set<String> migratedModules;

    /**
     * @param root the root package
     * @param domainModules the modules rule 4 keeps free of Quarkus, Jakarta, JDBC and Jackson
     * @param migratedModules the modules rules 2 and 3 apply to
     */
    ModuleRules(String root, Set<String> domainModules, Set<String> migratedModules) {
        this.root = root;
        this.domainModules = domainModules;
        this.migratedModules = migratedModules;
    }

    /** Rule 1: there are no dependency cycles between modules. */
    ArchRule noCycles() {
        return slices().matching(root + ".(*)..").namingSlices("module $1")
                .should().beFreeOfCycles()
                .as("Rule 1: there are no dependency cycles between the modules of " + root);
    }

    /** Rule 4: the domain modules don't depend on Quarkus, Jakarta (JAX-RS among it), JDBC or Jackson. */
    ArchRule domainIsPure() {
        return domainModules.stream().sorted()
                .map(module -> noClasses().that().resideInAPackage(root + "." + module + "..")
                        .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
                        .as("Rule 4: module " + module + " is domain code, so it doesn't depend on Quarkus,"
                                + " Jakarta/JAX-RS, JDBC or Jackson"))
                .map(CompositeArchRule::of)
                .reduce(CompositeArchRule::and)
                .orElseThrow(() -> new IllegalStateException("rule 4 needs at least one domain module"));
    }
}
