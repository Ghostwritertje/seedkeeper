package io.github.ghostwritertje.seedkeeper.pit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;

/** Which of seedkeeper's own test classes PIT may run: only the ones that don't start the app. */
class SkipTestsThatStartTheAppTest {

    private static final String TESTS = "io.github.ghostwritertje.seedkeeper.";

    static Stream<Arguments> testClasses() {
        return Stream.of(
                Arguments.argumentSet("@QuarkusTest", TESTS + "transmission.TransmissionClientTest", true),
                Arguments.argumentSet("@QuarkusIntegrationTest", TESTS + "groups.GroupsIT", true),
                Arguments.argumentSet("a QuarkusUnitTest extension", TESTS + "startup.UnusableDataDirTest", true),
                Arguments.argumentSet("a plain JUnit test", TESTS + "decide.DecideTest", false),
                Arguments.argumentSet("a plain JUnit test with a temp dir", TESTS + "keep.OverrideStoreTest", false));
    }

    @ParameterizedTest
    @MethodSource("testClasses")
    void skipsATestClassOnlyWhenItStartsTheApp(String testClass, boolean skipped) throws ClassNotFoundException {
        // Not initialised: a QuarkusUnitTest builds its application in a static initialiser.
        TestDescriptor descriptor =
                new ClassDescriptor(Class.forName(testClass, false, getClass().getClassLoader()));

        assertEquals(skipped, new SkipTestsThatStartTheApp().apply(descriptor).excluded());
    }

    /** A test class as a JUnit engine reports it after discovery. */
    private static final class ClassDescriptor extends AbstractTestDescriptor {

        ClassDescriptor(Class<?> testClass) {
            super(UniqueId.root("class", testClass.getName()), testClass.getSimpleName(), ClassSource.from(testClass));
        }

        @Override
        public Type getType() {
            return Type.CONTAINER;
        }
    }
}
