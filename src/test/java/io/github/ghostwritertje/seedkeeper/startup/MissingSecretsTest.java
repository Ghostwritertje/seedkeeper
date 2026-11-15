package io.github.ghostwritertje.seedkeeper.startup;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Without the seedkeeper secrets the application doesn't start, and the failure names each missing one. */
class MissingSecretsTest {

    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
            .withApplicationRoot(Startup::application)
            .overrideConfigKey("seedkeeper.transmission.url", "http://127.0.0.1:9091/transmission/rpc")
            .overrideConfigKey("seedkeeper.downloads-root", "/downloads")
            .overrideConfigKey("seedkeeper.library-roots", "/library")
            .overrideConfigKey("quarkus.http.auth.session.encryption-key", "a-session-encryption-key-for-tests")
            .assertException(failure -> {
                String message = Startup.messages(failure);
                assertTrue(message.contains("seedkeeper.transmission.username"), message);
                assertTrue(message.contains("seedkeeper.transmission.password"), message);
                assertTrue(message.contains("seedkeeper.auth.password"), message);
                assertTrue(message.contains("seedkeeper.api-key"), message);
            });

    @Test
    void doesNotStart() {
        fail("the application started without its secrets");
    }
}
