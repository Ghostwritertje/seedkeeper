package io.github.ghostwritertje.seedkeeper;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Refuses to start without the session encryption key. Quarkus would otherwise make up a new key at every start,
 * which logs everyone out. The other secrets are required keys of {@link SeedkeeperConfig}.
 */
@ApplicationScoped
public class RequiredSecrets {

    void check(@Observes StartupEvent start,
            @ConfigProperty(name = "quarkus.http.auth.session.encryption-key") Optional<String> sessionEncryptionKey) {
        if (sessionEncryptionKey.filter(key -> !key.isBlank()).isEmpty()) {
            throw new IllegalStateException("The session encryption key is missing:"
                    + " set QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY in the environment");
        }
    }
}
