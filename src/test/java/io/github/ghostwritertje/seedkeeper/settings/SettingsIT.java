package io.github.ghostwritertje.seedkeeper.settings;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Runs the same checks as {@link SettingsTest} against the packaged (native) application. */
@QuarkusIntegrationTest
class SettingsIT extends SettingsTest {}
