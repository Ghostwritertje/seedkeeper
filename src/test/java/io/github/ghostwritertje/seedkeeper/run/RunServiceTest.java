package io.github.ghostwritertje.seedkeeper.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.decide.TrackerRules;
import io.github.ghostwritertje.seedkeeper.overrides.KeepOverride;
import io.github.ghostwritertje.seedkeeper.overrides.OverrideStore;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Status;
import io.github.ghostwritertje.seedkeeper.transmission.StubTransmission;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The run lock and the memory of the last run (spec §5, step 1). */
@Timeout(30)
class RunServiceTest {

    @TempDir
    Path root;

    private final StubTransmission transmission = new StubTransmission();

    @Test
    void aRunGivesItsOutcomeAndIsRememberedAsTheLastRun() {
        RunService service = service();
        assertEquals(Optional.empty(), service.lastRun());

        Optional<RunOutcome> outcome = service.run();

        assertEquals(Status.SUCCEEDED, outcome.orElseThrow().status());
        assertEquals(outcome, service.lastRun());
        assertFalse(service.running());
    }

    @Test
    void aFailedRunIsRememberedTooAndTheNextRunCanStart() {
        RunService service = service();
        transmission.unreachable("Transmission does not answer");

        assertEquals(Status.FAILED, service.run().orElseThrow().status());
        assertEquals(Optional.of("Transmission does not answer"), service.lastRun().orElseThrow().error());
        assertTrue(service.run().isPresent(), "the lock was not released after a failed run");
    }

    @Test
    void whileARunIsRunningAnotherOneIsRefused() throws Exception {
        RunService service = service();
        CountDownLatch inTheRun = new CountDownLatch(1);
        CountDownLatch mayGoOn = new CountDownLatch(1);
        transmission.onSession(() -> {
            inTheRun.countDown();
            try {
                mayGoOn.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CompletableFuture<Optional<RunOutcome>> first = CompletableFuture.supplyAsync(service::run);
        assertTrue(inTheRun.await(10, TimeUnit.SECONDS));

        assertTrue(service.running());
        assertEquals(Optional.empty(), service.run(), "a second run started while the first was running");
        assertEquals(Optional.empty(), service.lastRun());

        mayGoOn.countDown();
        RunOutcome outcome = first.get(10, TimeUnit.SECONDS).orElseThrow();
        assertEquals(Status.SUCCEEDED, outcome.status());
        assertEquals(Optional.of(outcome), service.lastRun());
        assertFalse(service.running());
        assertTrue(service.run().isPresent(), "the lock was not released");
    }

    /**
     * Spec §5, step 4. The override was set on {@code original}; since then {@code cross-seed}, a symlink to its
     * file, joined the group.
     */
    @Test
    void aRunAddsTheHashOfANewCrossSeedMemberToTheOverrideThatMatchesItsGroup() throws IOException {
        Path original = Files.createDirectories(root.resolve("Original")).resolve("movie.mkv");
        Files.writeString(original, "data");
        Files.createSymbolicLink(Files.createDirectories(root.resolve("cross-seed")).resolve("movie.mkv"), original);
        Files.writeString(Files.createDirectories(root.resolve("Other")).resolve("movie.mkv"), "data");
        transmission.add(torrent("original", "Original/movie.mkv"), torrent("cross-seed", "cross-seed/movie.mkv"),
                torrent("other", "Other/movie.mkv"));
        Files.writeString(overridesFile(), """
                overrides:
                  - id: one
                    hashes: [gone, original]
                    note: Rewatching
                    until: 2999-12-31
                  - id: stale
                    hashes: [left]
                """);

        RunOutcome outcome = service().run().orElseThrow();

        assertEquals(Status.SUCCEEDED, outcome.status());
        assertEquals(List.of(
                new KeepOverride("one", List.of("gone", "original", "cross-seed"), "Rewatching",
                        Optional.of(LocalDate.parse("2999-12-31")), Optional.empty()),
                new KeepOverride("stale", List.of("left"), "", Optional.empty(), Optional.empty())),
                new OverrideStore(overridesFile()).overrides());
    }

    @Test
    void aRunThatAddsNoHashLeavesTheOverridesFileAsItIs() throws IOException {
        Files.writeString(Files.createDirectories(root.resolve("Original")).resolve("movie.mkv"), "data");
        transmission.add(torrent("original", "Original/movie.mkv"));
        String byHand = "overrides:\n  - id: one   # by hand\n    hashes: [original]\n";
        Files.writeString(overridesFile(), byHand);

        service().run();

        assertEquals(byHand, Files.readString(overridesFile()));
    }

    @Test
    void anExpiredOverrideStillGetsTheNewMembersOfItsGroup() throws IOException {
        Path original = Files.createDirectories(root.resolve("Original")).resolve("movie.mkv");
        Files.writeString(original, "data");
        Files.createSymbolicLink(Files.createDirectories(root.resolve("cross-seed")).resolve("movie.mkv"), original);
        transmission.add(torrent("original", "Original/movie.mkv"), torrent("cross-seed", "cross-seed/movie.mkv"));
        Files.writeString(overridesFile(), "overrides:\n  - id: one\n    hashes: [original]\n    until: 2020-01-01\n");

        service().run();

        assertEquals(List.of("original", "cross-seed"),
                new OverrideStore(overridesFile()).overrides().getFirst().hashes());
    }

    private Path overridesFile() {
        return root.resolve("overrides.yaml");
    }

    /** A seeding torrent of no managed tracker in the root: unmanaged, so a run leaves it alone. */
    private Torrent torrent(String hash, String file) {
        return new Torrent(hash, hash, Torrent.Status.SEEDING, 1, 0, "", 0, true, root.toString(),
                List.of("https://elsewhere.example/announce"), List.of(new Torrent.File(file, 4, true)));
    }

    private RunService service() {
        TrackerRules trackers = new TrackerRules(List.of(), Duration.ofDays(30), Optional.empty());
        OverrideStore overrides = new OverrideStore(overridesFile());
        return new RunService(new Run(transmission, trackers, root, List.of(), ZoneOffset.UTC, overrides), overrides);
    }
}
