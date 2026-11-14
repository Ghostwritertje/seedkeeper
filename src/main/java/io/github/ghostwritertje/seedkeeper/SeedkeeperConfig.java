package io.github.ghostwritertje.seedkeeper;

import io.smallrye.config.ConfigMapping;
import java.net.URI;

/**
 * seedkeeper's configuration: {@code seedkeeper.yaml} for everything that is safe to commit, the environment for the
 * secrets. A key without a default is required: the application doesn't start without it.
 */
@ConfigMapping(prefix = "seedkeeper")
public interface SeedkeeperConfig {

    Transmission transmission();

    Auth auth();

    /** Secret, env {@code SEEDKEEPER_API_KEY}. */
    String apiKey();

    interface Transmission {

        /** Transmission's RPC endpoint, for example {@code http://transmission:9091/transmission/rpc}. */
        URI url();

        /** Secret, env {@code SEEDKEEPER_TRANSMISSION_USERNAME}. */
        String username();

        /** Secret, env {@code SEEDKEEPER_TRANSMISSION_PASSWORD}. */
        String password();
    }

    interface Auth {

        /** Secret, env {@code SEEDKEEPER_AUTH_PASSWORD}. */
        String password();
    }
}
