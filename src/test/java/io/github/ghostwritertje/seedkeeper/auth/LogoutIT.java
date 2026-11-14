package io.github.ghostwritertje.seedkeeper.auth;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Runs the same checks as {@link LogoutTest} against the packaged (native) application. */
@QuarkusIntegrationTest
class LogoutIT extends LogoutTest {
}
