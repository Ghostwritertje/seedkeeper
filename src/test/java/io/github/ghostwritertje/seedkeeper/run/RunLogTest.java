package io.github.ghostwritertje.seedkeeper.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ghostwritertje.seedkeeper.decide.TrackerRules;
import io.github.ghostwritertje.seedkeeper.decide.TrackerRules.ManagedTracker;
import io.github.ghostwritertje.seedkeeper.transmission.StubTransmission;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.ghostwritertje.seedkeeper.overrides.OverrideStore;

/** What a run logs (spec §10): one line per run start and end, removal, deleted file and error. */
class RunLogTest {

    private static final long DAY = 86_400;

    @TempDir
    Path root;

    private final StubTransmission transmission = new StubTransmission();
    private final List<String> lines = new ArrayList<>();
    private final Logger logger = Logger.getLogger(RunMonitor.class.getName());
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            lines.add(record.getLevel() + " " + MDC.get(RunMonitor.EVENT) + ": " + record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void listen() {
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @AfterEach
    void stopListening() {
        logger.removeHandler(handler);
    }

    @Test
    void aRunLogsItsStartEachRemovalEachDeletedFileAndItsEnd() throws IOException {
        Path first = file(root.resolve("Due/a.mkv"));
        Path second = file(root.resolve("Due/b.mkv"));
        file(root.resolve("Fresh/a.mkv"));
        transmission.add(torrent("due", "Due", 30 * DAY, "Due/a.mkv", "Due/b.mkv"),
                torrent("fresh", "Fresh", DAY, "Fresh/a.mkv"));

        service().run();

        assertEquals(List.of(
                "INFO run-start: Run started",
                "INFO removal: Removed Due (all-met): 1 torrents, 2 files deleted, 8 bytes freed",
                "INFO deleted-file: Deleted " + first,
                "INFO deleted-file: Deleted " + second,
                "INFO run-end: Run ended: succeeded, 2 groups, 1 removals carried out, 0 failed, 8 bytes freed"), lines);
    }

    @Test
    void aRunThatFailsLogsTheReasonAsAnError() {
        transmission.unreachable("Transmission does not answer");

        service().run();

        assertEquals(List.of(
                "INFO run-start: Run started",
                "ERROR run-end: Run ended: failed: Transmission does not answer"), lines);
    }

    @Test
    void aRemovalThatFailsIsLoggedAsAnErrorAndNoFileIsLoggedAsDeleted() throws IOException {
        file(root.resolve("Due/a.mkv"));
        transmission.add(torrent("due", "Due", 30 * DAY, "Due/a.mkv"));
        transmission.refuseRemovalOf("due");

        service().run();

        assertEquals(3, lines.size(), lines.toString());
        assertEquals("INFO run-start: Run started", lines.get(0));
        assertEquals(true, lines.get(1).startsWith(
                "ERROR removal: Removal of Due (all-met) failed after 0 of 1 torrents, no files deleted: "), lines.get(1));
        assertEquals("INFO run-end: Run ended: succeeded, 1 groups, 0 removals carried out, 1 failed, 0 bytes freed",
                lines.get(2));
    }

    @Test
    void aPathThatIsRefusedIsLoggedAsAnErrorOfItsOwnKind(@TempDir Path elsewhere) throws IOException {
        Path outside = file(elsewhere.resolve("Due/a.mkv"));
        transmission.add(new Torrent("due", "Due", Torrent.Status.SEEDING, 1, 0, "", 30 * DAY, true,
                elsewhere.toString(), List.of("https://tracker-a.example/announce"),
                List.of(new Torrent.File("Due/a.mkv", 4, true))));

        service().run();

        assertEquals("ERROR refused-path: Refused to delete " + outside, lines.get(2), lines.toString());
    }

    private RunService service() {
        TrackerRules trackers = new TrackerRules(
                List.of(new ManagedTracker("tracker-a", List.of("tracker-a.example"), Optional.empty())),
                Duration.ofDays(30), Optional.empty());
        OverrideStore overrides = new OverrideStore(root.resolve("overrides.yaml"));
        return new RunService(new Run(transmission, trackers, root, List.of(), ZoneOffset.UTC, overrides), overrides,
                new RunMonitor(new SimpleMeterRegistry()));
    }

    private Torrent torrent(String hash, String name, long secondsSeeding, String... files) {
        return new Torrent(hash, name, Torrent.Status.SEEDING, 1, 0, "", secondsSeeding, true, root.toString(),
                List.of("https://tracker-a.example/announce"),
                Arrays.stream(files).map(file -> new Torrent.File(file, 4, true)).toList());
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }
}
