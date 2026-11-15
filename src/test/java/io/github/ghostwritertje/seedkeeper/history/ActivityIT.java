package io.github.ghostwritertje.seedkeeper.history;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Runs the same checks as {@link ActivityTest} against the packaged (native) application: SQLite and Flyway in the
 * native executable.
 */
@QuarkusIntegrationTest
class ActivityIT extends ActivityTest {
}
