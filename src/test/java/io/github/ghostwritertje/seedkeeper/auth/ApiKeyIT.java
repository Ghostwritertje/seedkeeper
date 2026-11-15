package io.github.ghostwritertje.seedkeeper.auth;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Runs the same checks as {@link ApiKeyTest} against the packaged (native) application. */
@QuarkusIntegrationTest
class ApiKeyIT extends ApiKeyTest {
}
