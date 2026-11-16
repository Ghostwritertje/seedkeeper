package io.github.ghostwritertje.seedkeeper.system;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Runs the same checks as {@link SystemTest} against the packaged (native) application. */
@QuarkusIntegrationTest
class SystemIT extends SystemTest {}
