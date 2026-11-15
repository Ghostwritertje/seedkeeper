package io.github.ghostwritertje.seedkeeper.startup;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** With a data dir that can't be created the application doesn't start, and the failure names the key. */
class UnusableDataDirTest {

    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
            // With application.properties: that is where the database is put in the data dir.
            .withApplicationRoot(jar -> Startup.application(jar.addAsResource("application.properties")))
            .overrideConfigKey("seedkeeper.transmission.url", "http://127.0.0.1:9091/transmission/rpc")
            .overrideConfigKey("seedkeeper.library-roots", "/library")
            .overrideConfigKey("seedkeeper.transmission.username", "transmission-user")
            .overrideConfigKey("seedkeeper.transmission.password", "transmission-password")
            .overrideConfigKey("seedkeeper.auth.password", "ui-password")
            .overrideConfigKey("seedkeeper.api-key", "api-key")
            .overrideConfigKey("quarkus.http.auth.session.encryption-key", "a-session-encryption-key-for-tests")
            // Under a file, so it can't be created, whoever runs the tests.
            .overrideConfigKey("seedkeeper.data-dir", "/dev/null/data")
            .assertException(failure -> {
                String message = Startup.messages(failure);
                assertTrue(message.contains("The data dir /dev/null/data (seedkeeper.data-dir) can't be used"),
                        message);
            });

    @Test
    void doesNotStart() {
        fail("the application started without a data dir it can write to");
    }
}
