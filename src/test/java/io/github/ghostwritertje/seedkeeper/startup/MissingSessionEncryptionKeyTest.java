package io.github.ghostwritertje.seedkeeper.startup;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** With every seedkeeper secret set but no session encryption key, the application doesn't start either. */
class MissingSessionEncryptionKeyTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(Startup::application)
            .overrideConfigKey("seedkeeper.transmission.url", "http://127.0.0.1:9091/transmission/rpc")
            .overrideConfigKey("seedkeeper.library-roots", "/library")
            .overrideConfigKey("seedkeeper.transmission.username", "transmission-user")
            .overrideConfigKey("seedkeeper.transmission.password", "transmission-password")
            .overrideConfigKey("seedkeeper.auth.password", "ui-password")
            .overrideConfigKey("seedkeeper.api-key", "api-key")
            .assertException(failure -> {
                String message = Startup.messages(failure);
                assertTrue(message.contains("QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY"), message);
            });

    @Test
    void doesNotStart() {
        fail("the application started without the session encryption key");
    }
}
