package io.github.ghostwritertje.seedkeeper;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * seedkeeper's configuration: {@code seedkeeper.yaml} for everything that is safe to commit, the environment for the
 * secrets. A key without a default is required: the application doesn't start without it.
 */
@ConfigMapping(prefix = "seedkeeper")
public interface SeedkeeperConfig {

    /** When the scheduled run starts: a Quarkus cron expression, in {@link #timeZone()}. */
    @WithDefault("0 0 6 * * ?")
    String schedule();

    /** The time zone of the schedule, of the forecast and of the end dates of keep overrides. */
    @WithDefault("UTC")
    ZoneId timeZone();

    Transmission transmission();

    Auth auth();

    /** Secret, env {@code SEEDKEEPER_API_KEY}. */
    String apiKey();

    /** Orphans are looked for here; nothing outside it is deleted. */
    @WithDefault("/downloads")
    Path downloadsRoot();

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

    /** Where seedkeeper keeps {@code overrides.yaml} and {@code seedkeeper.db}. */
    @WithDefault("/data")
    Path dataDir();

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
