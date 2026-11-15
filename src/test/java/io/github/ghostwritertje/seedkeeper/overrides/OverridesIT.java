package io.github.ghostwritertje.seedkeeper.overrides;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Runs the same checks as {@link OverridesTest} against the packaged (native) application: reading the YAML and the
 * file watcher have to work there too.
 */
@QuarkusIntegrationTest
class OverridesIT extends OverridesTest {
}
