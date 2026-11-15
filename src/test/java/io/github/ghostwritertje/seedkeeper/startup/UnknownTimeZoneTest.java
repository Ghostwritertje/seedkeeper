package io.github.ghostwritertje.seedkeeper.startup;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** With a time zone that doesn't exist the application doesn't start, and the failure names it. */
class UnknownTimeZoneTest {

    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
            .withApplicationRoot(Startup::application)
            .overrideConfigKey("seedkeeper.transmission.url", "http://127.0.0.1:9091/transmission/rpc")
            .overrideConfigKey("seedkeeper.library-roots", "/library")
            .overrideConfigKey("seedkeeper.transmission.username", "transmission-user")
            .overrideConfigKey("seedkeeper.transmission.password", "transmission-password")
            .overrideConfigKey("seedkeeper.auth.password", "ui-password")
            .overrideConfigKey("seedkeeper.api-key", "api-key")
            .overrideConfigKey("quarkus.http.auth.session.encryption-key", "a-session-encryption-key-for-tests")
            .overrideConfigKey("seedkeeper.time-zone", "Europe/Brussel")
            .assertException(failure -> {
                String message = Startup.messages(failure);
                assertTrue(message.contains("Unknown time-zone ID: Europe/Brussel"), message);
            });

    @Test
    void doesNotStart() {
        fail("the application started with a time zone that doesn't exist");
    }
}
