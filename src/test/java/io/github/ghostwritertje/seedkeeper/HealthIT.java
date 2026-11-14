package io.github.ghostwritertje.seedkeeper;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Runs the same checks as {@link HealthTest} against the packaged (native) application. */
@QuarkusIntegrationTest
class HealthIT extends HealthTest {
}
