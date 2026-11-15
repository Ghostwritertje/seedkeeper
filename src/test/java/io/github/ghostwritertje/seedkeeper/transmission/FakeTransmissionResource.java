package io.github.ghostwritertje.seedkeeper.transmission;

import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Map;

/**
 * Starts a {@link FakeTransmission} and points the application at it, with every secret set, a downloads directory
 * and a library root in a temporary directory, and these trackers: {@code tracker-a} (default minimum),
 * {@code tracker-b} (14 days) and {@code public} (1 day). The time zone is {@link #TIME_ZONE}.
 */
public class FakeTransmissionResource implements QuarkusTestResourceLifecycleManager {

    /** The time zone of the application under test: always UTC+05:30, and not the zone of whoever runs the tests. */
    public static final ZoneId TIME_ZONE = ZoneId.of("Asia/Kolkata");

    private static volatile FakeTransmission fake;
    private static volatile Path downloads;
    private static volatile Path library;

    /** The fake the application under test talks to. */
    public static FakeTransmission fake() {
        return fake;
    }

    /** An empty directory to put downloaded files in. */
    public static Path downloads() {
        return downloads;
    }

    /** The one library root of the application under test. */
    public static Path library() {
        return library;
    }

    @Override
    public Map<String, String> start() {
        fake = new FakeTransmission();
        try {
            Path root = Files.createTempDirectory("seedkeeper-test");
            downloads = Files.createDirectory(root.resolve("downloads"));
            library = Files.createDirectory(root.resolve("library"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> config = TestSecrets.with("seedkeeper.transmission.url", fake.url().toString());
        config.put("seedkeeper.library-roots", library.toString());
        config.put("seedkeeper.time-zone", TIME_ZONE.getId());
        config.put("seedkeeper.trackers.tracker-a.hosts", "tracker-a.example");
        config.put("seedkeeper.trackers.tracker-b.hosts", "tracker-b.example,tracker-b.example.org");
        config.put("seedkeeper.trackers.tracker-b.minimum-seed-time", "14d");
        config.put("seedkeeper.public.minimum-seed-time", "1d");
        return config;
    }

    @Override
    public void stop() {
        if (fake != null) {
            fake.close();
        }
    }
}
