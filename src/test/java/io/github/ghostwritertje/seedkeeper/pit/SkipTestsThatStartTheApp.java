package io.github.ghostwritertje.seedkeeper.pit;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Keeps the tests that start the app out of PIT: {@code @QuarkusTest}, {@code @QuarkusIntegrationTest} and the
 * {@code QuarkusExtensionTest} extension. Starting the app for every mutant would make mutation testing far too slow.
 *
 * <p>Only PIT loads this filter: the quality profile puts {@code src/test/pit} on PIT's classpath, and that
 * directory registers it with the JUnit Platform. Surefire never sees it.
 */
public final class SkipTestsThatStartTheApp implements PostDiscoveryFilter {

    /** Quarkus' test framework: an annotation or extension from here means the test starts the app. */
    private static final String QUARKUS_TEST = "io.quarkus.test.";

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        return descriptor
                .getSource()
                .flatMap(SkipTestsThatStartTheApp::testClass)
                .filter(SkipTestsThatStartTheApp::startsTheApp)
                .map(testClass -> FilterResult.excluded(testClass.getName() + " starts the app"))
                .orElseGet(() -> FilterResult.included("doesn't start the app"));
    }

    private static Optional<Class<?>> testClass(TestSource source) {
        return switch (source) {
            case ClassSource classSource -> Optional.of(classSource.getJavaClass());
            case MethodSource methodSource -> Optional.of(methodSource.getJavaClass());
            default -> Optional.empty();
        };
    }

    private static boolean startsTheApp(Class<?> testClass) {
        return Arrays.stream(testClass.getAnnotations())
                        .map(Annotation::annotationType)
                        .anyMatch(SkipTestsThatStartTheApp::isQuarkusTest)
                || Arrays.stream(testClass.getDeclaredFields())
                        .filter(field -> field.isAnnotationPresent(RegisterExtension.class))
                        .map(Field::getType)
                        .anyMatch(SkipTestsThatStartTheApp::isQuarkusTest);
    }

    private static boolean isQuarkusTest(Class<?> type) {
        return type.getName().startsWith(QUARKUS_TEST);
    }
}
