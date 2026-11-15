package io.github.ghostwritertje.seedkeeper.auth;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Runs the same checks as {@link LoginTest} against the packaged (native) application. */
@QuarkusIntegrationTest
class LoginIT extends LoginTest {
}
