package io.github.ghostwritertje.seedkeeper;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** The time zone dates are shown in, as a tz database name; {@link TimeZoneCheck} refuses an unknown one. */
    @WithDefault("Europe/Brussels")
    String timeZone();

    /** The roots of the media library. A downloaded file that is also linked under one of them has a library link. */
    List<Path> libraryRoots();

    /** The minimum seed time of a tracker that doesn't set its own. */
    @WithDefault("30d")
    Duration defaultMinimumSeedTime();

    /** The managed trackers, by name. */
    Map<String, Tracker> trackers();

    /** The tracker of the torrents that are not private; without it such torrents are unmanaged. */
    @WithName("public")
    Optional<PublicTracker> publicTracker();

    interface Tracker {

        /** The hosts of this tracker's announce URLs. */
        List<String> hosts();

        /** This tracker's minimum seed time; absent means the default. */
        Optional<Duration> minimumSeedTime();
    }

    interface PublicTracker {

        Duration minimumSeedTime();
    }

    interface Transmission {

        /** Transmission's RPC endpoint, for example {@code http://transmission:9091/transmission/rpc}. */
        URI url();

        /** Secret, env {@code SEEDKEEPER_TRANSMISSION_USERNAME}. */
        String username();

        /** Secret, env {@code SEEDKEEPER_TRANSMISSION_PASSWORD}. */
        String password();
    }

    interface Auth {

        /** The name of the one user that can log in. */
        @WithDefault("admin")
        String username();

        /** Secret, env {@code SEEDKEEPER_AUTH_PASSWORD}. */
        String password();
    }
}
