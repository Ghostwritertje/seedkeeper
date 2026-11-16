package io.github.ghostwritertje.seedkeeper.transmission;

import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Starts a {@link FakeTransmission} and points the application at it, with every secret set, a downloads directory,
 * a library root and a data dir in a temporary directory, and these trackers: {@code tracker-a} (default minimum),
 * {@code tracker-b} (14 days) and {@code public} (1 day). The time zone is {@link #TIME_ZONE}; the scheduled run is
 * {@link #SCHEDULE}.
 */
public class FakeTransmissionResource implements QuarkusTestResourceLifecycleManager {

    /** The time zone of the application under test: always UTC+05:30, and not the zone of whoever runs the tests. */
    public static final ZoneId TIME_ZONE = ZoneId.of("Asia/Kolkata");

    /** 06:00 on 1 January: a schedule that is there, and that doesn't start a run in the middle of a test. */
    public static final String SCHEDULE = "0 0 6 1 1 ?";

    // Null until start().
    private static volatile @Nullable FakeTransmission fake;
    private static volatile @Nullable Path downloads;
    private static volatile @Nullable Path library;
    private static volatile @Nullable Path dataDir;

    /** The fake the application under test talks to. */
    public static FakeTransmission fake() {
        return started(fake);
    }

    /** The downloads root of the application under test: a directory to put downloaded files in. */
    public static Path downloads() {
        return started(downloads);
    }

    /** The one library root of the application under test. */
    public static Path library() {
        return started(library);
    }

    /** The data dir of the application under test: where it keeps {@code overrides.yaml} and {@code seedkeeper.db}. */
    public static Path dataDir() {
        return started(dataDir);
    }

    private static <T> T started(@Nullable T value) {
        return Objects.requireNonNull(value, "FakeTransmissionResource has not started");
    }

    @Override
    public Map<String, String> start() {
        FakeTransmission transmission = new FakeTransmission();
        fake = transmission;
        Path downloadsRoot;
        Path libraryRoot;
        Path data;
        try {
            Path root = Files.createTempDirectory("seedkeeper-test");
            downloadsRoot = Files.createDirectory(root.resolve("downloads"));
            libraryRoot = Files.createDirectory(root.resolve("library"));
            data = Files.createDirectory(root.resolve("data"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        downloads = downloadsRoot;
        library = libraryRoot;
        dataDir = data;
        Map<String, String> config = TestSecrets.with(
                "seedkeeper.transmission.url", transmission.url().toString());
        config.put("seedkeeper.downloads-root", downloadsRoot.toString());
        config.put("seedkeeper.library-roots", libraryRoot.toString());
        config.put("seedkeeper.data-dir", data.toString());
        config.put("seedkeeper.time-zone", TIME_ZONE.getId());
        config.put("seedkeeper.schedule", SCHEDULE);
        config.put("seedkeeper.trackers.tracker-a.hosts", "tracker-a.example");
        config.put("seedkeeper.trackers.tracker-b.hosts", "tracker-b.example,tracker-b.example.org");
        config.put("seedkeeper.trackers.tracker-b.minimum-seed-time", "14d");
        config.put("seedkeeper.public.minimum-seed-time", "1d");
        return config;
    }

    @Override
    public void stop() {
        FakeTransmission started = fake;
        if (started != null) {
            started.close();
        }
    }
}
