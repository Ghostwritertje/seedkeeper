package io.github.ghostwritertje.seedkeeper.overrides;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Runs the same checks as {@link ManageOverridesTest} against the packaged (native) application. */
@QuarkusIntegrationTest
class ManageOverridesIT extends ManageOverridesTest {}
