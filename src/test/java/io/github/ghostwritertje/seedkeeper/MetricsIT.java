package io.github.ghostwritertje.seedkeeper;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Runs the same checks as {@link MetricsTest} against the packaged (native) application. */
@QuarkusIntegrationTest
class MetricsIT extends MetricsTest {}
