package io.github.ghostwritertje.seedkeeper;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaPackage;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.Slice;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The deep-module rules for the modules under one root package. A module is a top-level subpackage of the root,
 * together with its subpackages; the root package itself holds only app wiring and is no module.
 */
final class ModuleRules {

    /** How many module API types that aren't records or enums a module may have. */
    static final int API_BUDGET = 3;

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
        return slices().matching(root + ".(*)..")
                .namingSlices("module $1")
                .should()
                .beFreeOfCycles()
                .as("Rule 1: there are no dependency cycles between the modules of " + root);
    }

    /**
     * Rule 2: a type of a migrated module may be used from another module only if it is {@link ModuleApi}, or a record
     * or enum in the public signatures of one (or of such a record, in turn).
     */
    ArchRule onlyApiAcrossModules() {
        return classes()
                .that()
                .resideInAnyPackage(packagesOf(migratedModules))
                .should(new ArchCondition<JavaClass>("be used from another module only when it is module API") {
                    @Override
                    public void check(JavaClass used, ConditionEvents events) {
                        String module = moduleOf(used).orElseThrow();
                        if (usableFromOtherModules(used)) {
                            return;
                        }
                        for (Dependency use : used.getDirectDependenciesToSelf()) {
                            JavaClass user = use.getOriginClass();
                            moduleOf(user)
                                    .filter(other -> !other.equals(module))
                                    .ifPresent(other -> events.add(SimpleConditionEvent.violated(
                                            use,
                                            "Rule 2: <" + used.getName() + "> of module "
                                                    + module + " is used from <" + user.getName() + "> of module "
                                                    + other
                                                    + ", but it is neither @ModuleApi nor a record or enum in the public"
                                                    + " signatures of a @ModuleApi type: " + use.getDescription())));
                        }
                    }
                })
                .allowEmptyShould(true)
                .as("Rule 2: other modules use only the module API of migrated modules " + sorted(migratedModules));
    }

    /** Rule 3: a migrated module has at most {@value #API_BUDGET} module API types that aren't records or enums. */
    ArchRule apiBudget() {
        return slices().matching(root + ".(*)..")
                .namingSlices("module $1")
                .that(DescribedPredicate.describe(
                        "are migrated", (Slice slice) -> migratedModules.contains(slice.getNamePart(1))))
                .should(new ArchCondition<Slice>("have at most " + API_BUDGET + " module API behaviour types") {
                    @Override
                    public void check(Slice slice, ConditionEvents events) {
                        List<String> behaviour = slice.stream()
                                .filter(type -> isModuleApi(type) && !isRecordOrEnum(type))
                                .map(JavaClass::getName)
                                .sorted()
                                .toList();
                        if (behaviour.size() > API_BUDGET) {
                            events.add(SimpleConditionEvent.violated(
                                    slice,
                                    "Rule 3: module "
                                            + slice.getNamePart(1) + " has " + behaviour.size()
                                            + " @ModuleApi behaviour types, more than " + API_BUDGET
                                            + " (records and enums don't count): " + String.join(", ", behaviour)));
                        }
                    }
                })
                .allowEmptyShould(true)
                .as("Rule 3: migrated modules " + sorted(migratedModules) + " have at most " + API_BUDGET
                        + " module API behaviour types each");
    }

    /**
     * True when the type is module API, so other modules may use it: it is marked {@code @ModuleApi}, or it is a
     * record or enum in the public signature of a module API type of the same module.
     */
    private boolean usableFromOtherModules(JavaClass type) {
        String module = moduleOf(type).orElseThrow();
        Set<JavaClass> moduleApi = new HashSet<>();
        Deque<JavaClass> toVisit = new ArrayDeque<>();
        modulePackage(type).getClassesInPackageTree().stream()
                .filter(ModuleRules::isModuleApi)
                .forEach(toVisit::add);
        while (!toVisit.isEmpty()) {
            JavaClass next = toVisit.pop();
            if (moduleApi.add(next)) {
                publicSignatureTypes(next).stream()
                        .filter(signatureType -> isRecordOrEnum(signatureType)
                                && moduleOf(signatureType)
                                        .filter(module::equals)
                                        .isPresent())
                        .forEach(toVisit::add);
            }
        }
        return moduleApi.contains(type);
    }

    /** The module of a type under the root; empty for a type of the root package itself or outside the root. */
    private Optional<String> moduleOf(JavaClass type) {
        String name = type.getPackageName();
        if (!name.startsWith(root + ".")) {
            return Optional.empty();
        }
        String rest = name.substring(root.length() + 1);
        int dot = rest.indexOf('.');
        return Optional.of(dot < 0 ? rest : rest.substring(0, dot));
    }

    /** The top-level package of the module of a type under the root. */
    private JavaPackage modulePackage(JavaClass type) {
        JavaPackage modulePackage = type.getPackage();
        while (modulePackage
                .getParent()
                .map(parent -> !parent.getName().equals(root))
                .orElse(false)) {
            modulePackage = modulePackage.getParent().orElseThrow();
        }
        return modulePackage;
    }

    private static boolean isModuleApi(JavaClass type) {
        return type.isAnnotatedWith(ModuleApi.class);
    }

    private static boolean isRecordOrEnum(JavaClass type) {
        return type.isRecord() || type.isEnum();
    }

    /** Every type in the public methods, constructors and fields of a type, type arguments included. */
    private static Set<JavaClass> publicSignatureTypes(JavaClass type) {
        Set<JavaClass> types = new HashSet<>();
        for (JavaMethod method : type.getMethods()) {
            if (method.getModifiers().contains(JavaModifier.PUBLIC)) {
                types.addAll(method.getReturnType().getAllInvolvedRawTypes());
                method.getParameterTypes().forEach(parameter -> types.addAll(parameter.getAllInvolvedRawTypes()));
            }
        }
        for (JavaConstructor constructor : type.getConstructors()) {
            if (constructor.getModifiers().contains(JavaModifier.PUBLIC)) {
                constructor.getParameterTypes().forEach(parameter -> types.addAll(parameter.getAllInvolvedRawTypes()));
            }
        }
        for (JavaField field : type.getFields()) {
            if (field.getModifiers().contains(JavaModifier.PUBLIC)) {
                types.addAll(field.getType().getAllInvolvedRawTypes());
            }
        }
        return types;
    }

    private String[] packagesOf(Set<String> modules) {
        return modules.stream().map(module -> root + "." + module + "..").toArray(String[]::new);
    }

    private static List<String> sorted(Set<String> modules) {
        return modules.stream().sorted().toList();
    }

    /** Rule 4: the domain modules don't depend on Quarkus, Jakarta (JAX-RS among it), JDBC or Jackson. */
    ArchRule domainIsPure() {
        return domainModules.stream()
                .sorted()
                .map(module -> noClasses()
                        .that()
                        .resideInAPackage(root + "." + module + "..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(FRAMEWORKS)
                        .as("Rule 4: module " + module + " is domain code, so it doesn't depend on Quarkus,"
                                + " Jakarta/JAX-RS, JDBC or Jackson"))
                .map(CompositeArchRule::of)
                .reduce(CompositeArchRule::and)
                .orElseThrow(() -> new IllegalStateException("rule 4 needs at least one domain module"));
    }
}
