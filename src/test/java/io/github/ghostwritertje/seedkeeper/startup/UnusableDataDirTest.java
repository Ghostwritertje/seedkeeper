package io.github.ghostwritertje.seedkeeper.startup;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.QuarkusUnitTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** With a data dir it can't make or write to, the application doesn't start, and the failure names the key. */
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
            .overrideConfigKey("seedkeeper.data-dir", aFile() + "/data")
            .assertException(failure -> {
                String message = Startup.messages(failure);
                assertTrue(message.contains("seedkeeper.data-dir"), message);
                assertTrue(message.contains("seedkeeper.db"), message);
            });

    @Test
    void doesNotStart() {
        fail("the application started without a data dir it can use");
    }

    /** A directory can't be made under a regular file, whoever runs the tests. */
    private static String aFile() {
        try {
            return Files.createTempFile("seedkeeper-test", ".file").toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
