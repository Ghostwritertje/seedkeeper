package io.github.ghostwritertje.seedkeeper.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.decide.TrackerRules;
import io.github.ghostwritertje.seedkeeper.decide.TrackerRules.ManagedTracker;
import io.github.ghostwritertje.seedkeeper.overrides.OverrideStore;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Removal;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Status;
import io.github.ghostwritertje.seedkeeper.transmission.StubTransmission;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One run (spec §5): snapshot, verdicts, removal and deletion, against a Transmission in memory and a real disk. */
class RunTest {

    private static final long DAY = 86_400;
    private static final ZoneId ZONE = ZoneId.of("Europe/Brussels");
    /** One tracker, {@code tracker-a}, with a minimum seed time of 30 days. */
    private static final TrackerRules TRACKERS = new TrackerRules(
            List.of(new ManagedTracker("tracker-a", List.of("tracker-a.example"), Optional.empty())),
            Duration.ofDays(30),
            Optional.empty());

    @TempDir
    Path root;

    private Path downloads;
    private Path library;
    private final StubTransmission transmission = new StubTransmission();

    @BeforeEach
    void directories() throws IOException {
        downloads = Files.createDirectory(root.resolve("downloads"));
        library = Files.createDirectory(root.resolve("library"));
    }

    @Test
    void removesADueGroupFromTransmissionAndDeletesItsFiles() throws IOException {
        Path due = file(downloads.resolve("Due/movie.mkv"));
        Path fresh = file(downloads.resolve("Fresh/movie.mkv"));
        transmission.add(torrent("due", 30 * DAY, "Due/movie.mkv"), torrent("fresh", 29 * DAY, "Fresh/movie.mkv"));

        RunOutcome outcome = run();

        assertEquals(Status.SUCCEEDED, outcome.status());
        assertEquals(Optional.empty(), outcome.error());
        assertEquals(List.of("due"), transmission.removed());
        assertEquals(List.of("fresh"), transmission.hashes());
        assertGone(due, downloads.resolve("Due"));
        assertTrue(Files.exists(fresh), "the file of a kept group was deleted");

        assertEquals(
                List.of(Reason.ALL_MET, Reason.MINIMUM_NOT_REACHED),
                outcome.verdicts().stream().map(verdict -> verdict.reason()).toList());
        assertEquals(1, outcome.removals().size());
        Removal removal = outcome.removals().getFirst();
        assertEquals(Reason.ALL_MET, removal.reason());
        assertEquals(
                List.of("due"), removal.removed().stream().map(Torrent::hash).toList());
        assertEquals(Optional.empty(), removal.error());
        assertEquals(List.of(due), removal.deleted().files());
        assertEquals(4, removal.deleted().spaceFreed());
        assertFalse(outcome.started().isAfter(outcome.ended()));
        assertEquals(ZONE, outcome.started().getZone());
    }

    @Test
    void aGroupWithALibraryLinkIsNeitherRemovedNorDeleted() throws IOException {
        Path linked = file(downloads.resolve("Linked/movie.mkv"));
        Files.createLink(library.resolve("movie.mkv"), linked);
        transmission.add(torrent("linked", 400 * DAY, "Linked/movie.mkv"));

        RunOutcome outcome = run();

        assertEquals(Status.SUCCEEDED, outcome.status());
        assertEquals(List.of(), outcome.removals());
        assertEquals(List.of(), transmission.removed());
        assertTrue(Files.exists(linked));
    }

    @Test
    void aCrossSeedGroupLeavesAsAWholeAndFreesItsDataOnce() throws IOException {
        Path original = file(downloads.resolve("Original/movie.mkv"));
        Path link = downloads.resolve("cross-seed/movie.mkv");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, original);
        transmission.add(
                torrent("original", 31 * DAY, "Original/movie.mkv"),
                torrent("cross-seed", 30 * DAY, "cross-seed/movie.mkv"));

        RunOutcome outcome = run();

        assertEquals(List.of("original", "cross-seed"), transmission.removed());
        assertGone(original, link);
        assertEquals(1, outcome.removals().size());
        assertEquals(4, outcome.removals().getFirst().deleted().spaceFreed());
    }

    @Test
    void endsFailedWithTheReasonWhenTransmissionDoesNotAnswer() throws IOException {
        Path due = file(downloads.resolve("Due/movie.mkv"));
        transmission.add(torrent("due", 30 * DAY, "Due/movie.mkv")).unreachable("Transmission does not answer");

        RunOutcome outcome = run();

        assertEquals(Status.FAILED, outcome.status());
        assertEquals(Optional.of("Transmission does not answer"), outcome.error());
        assertEquals(List.of(), outcome.verdicts());
        assertEquals(List.of(), outcome.removals());
        assertEquals(List.of(), transmission.removed());
        assertTrue(Files.exists(due));
    }

    @Test
    void endsFailedWithTheReasonWhenTransmissionIsOlderThan41() {
        transmission.tooOld("Transmission 4.0.6 is older than 4.1");

        RunOutcome outcome = run();

        assertEquals(Status.FAILED, outcome.status());
        assertEquals(Optional.of("Transmission 4.0.6 is older than 4.1"), outcome.error());
        assertEquals(List.of(), outcome.verdicts());
    }

    @Test
    void endsFailedAndRemovesNothingWhenALibraryRootCannotBeRead() throws IOException {
        Path due = file(downloads.resolve("Due/movie.mkv"));
        transmission.add(torrent("due", 30 * DAY, "Due/movie.mkv"));
        Files.delete(library);

        RunOutcome outcome = run();

        assertEquals(Status.FAILED, outcome.status());
        assertTrue(
                outcome.error().orElseThrow().startsWith("Library root " + library + " can't be read"),
                outcome.error().toString());
        assertEquals(List.of(), transmission.removed());
        assertTrue(Files.exists(due));
    }

    @Test
    void endsFailedAndRemovesNothingWhenTheDownloadsRootCannotBeFound() throws IOException {
        // The downloads root is a mount that is gone; Transmission still has its torrent, and its data elsewhere.
        Path gone = root.resolve("gone");
        Path due = file(downloads.resolve("Due/movie.mkv"));
        transmission.add(torrent("due", 30 * DAY, "Due/movie.mkv"));

        RunOutcome outcome = new Run(
                        transmission, TRACKERS, gone, List.of(library), ZONE, new OverrideStore(overridesFile()))
                .execute();

        assertEquals(Status.FAILED, outcome.status());
        assertEquals(
                Optional.of("The downloads root " + gone + " (seedkeeper.downloads-root) can't be found"),
                outcome.error());
        assertEquals(List.of(), outcome.removals());
        assertEquals(List.of(), transmission.removed());
        assertTrue(Files.exists(due));
    }

    @Test
    void aFailedRemovalDeletesNoFilesOfThatGroupAndTheNextRunFinishesIt() throws IOException {
        Path first = file(downloads.resolve("Pack/movie.mkv"));
        Path second = downloads.resolve("cross-seed/movie.mkv");
        Files.createDirectories(second.getParent());
        Files.createLink(second, first);
        Path other = file(downloads.resolve("Other/movie.mkv"));
        transmission.add(
                torrent("first", 30 * DAY, "Pack/movie.mkv"),
                torrent("second", 30 * DAY, "cross-seed/movie.mkv"),
                torrent("other", 30 * DAY, "Other/movie.mkv"));
        transmission.refuseRemovalOf("second");

        RunOutcome outcome = run();

        // The run goes on: the group after the one that failed is removed.
        assertEquals(Status.SUCCEEDED, outcome.status());
        assertEquals(List.of("first", "other"), transmission.removed());
        assertTrue(Files.exists(first) && Files.exists(second), "a file of the group that failed was deleted");
        assertGone(other);
        Removal failed = outcome.removals().getFirst();
        assertEquals(
                List.of("first", "second"),
                failed.torrents().stream().map(Torrent::hash).toList());
        assertEquals(
                List.of("first"), failed.removed().stream().map(Torrent::hash).toList());
        assertEquals(Optional.of("Transmission refused torrent_remove: torrent is busy"), failed.error());
        assertEquals(List.of(), failed.deleted().files());
        assertEquals(0, failed.deleted().spaceFreed());
        assertEquals(Optional.empty(), outcome.removals().get(1).error());

        transmission.allowRemovalOf("second");
        RunOutcome next = run();

        assertEquals(List.of("first", "other", "second"), transmission.removed());
        assertEquals(List.of(), transmission.hashes());
        assertGone(second);
        assertEquals(1, next.removals().size());
        assertEquals(Optional.empty(), next.removals().getFirst().error());
    }

    @Test
    void aFailedRemovalOfTheFirstMemberLeavesTheWholeGroupForTheNextRun() throws IOException {
        Path file = file(downloads.resolve("Pack/movie.mkv"));
        Path link = downloads.resolve("cross-seed/movie.mkv");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, file);
        transmission.add(
                torrent("first", 30 * DAY, "Pack/movie.mkv"), torrent("second", 30 * DAY, "cross-seed/movie.mkv"));
        transmission.refuseRemovalOf("first");

        run();

        assertEquals(List.of(), transmission.removed());
        assertTrue(Files.exists(file) && Files.isSymbolicLink(link));

        transmission.allowRemovalOf("first");
        RunOutcome next = run();

        assertEquals(List.of("first", "second"), transmission.removed());
        assertGone(file, link);
        assertEquals(4, next.removals().getFirst().deleted().spaceFreed());
    }

    @Test
    void aKeepOverrideInTheFileKeepsAGroupThatIsDue() throws IOException {
        Path kept = file(downloads.resolve("Kept/movie.mkv"));
        Path due = file(downloads.resolve("Due/movie.mkv"));
        transmission.add(torrent("kept", 30 * DAY, "Kept/movie.mkv"), torrent("due", 30 * DAY, "Due/movie.mkv"));
        overrides("overrides:\n  - id: one\n    hashes: [kept]\n    until: 2999-12-31\n");

        RunOutcome outcome = run();

        assertEquals(Status.SUCCEEDED, outcome.status());
        assertEquals(
                List.of(Reason.KEEP_OVERRIDE, Reason.ALL_MET),
                outcome.verdicts().stream().map(verdict -> verdict.reason()).toList());
        assertEquals(List.of("due"), transmission.removed());
        assertTrue(Files.exists(kept), "the file of a group with a keep override was deleted");
        assertGone(due);
    }

    @Test
    void theOverridesFileIsReadAtTheStartOfEveryRun() throws IOException {
        Path file = file(downloads.resolve("Kept/movie.mkv"));
        transmission.add(torrent("kept", 30 * DAY, "Kept/movie.mkv"));
        Run run = run(new OverrideStore(overridesFile()));
        overrides("overrides:\n  - id: one\n    hashes: [kept]\n");

        assertEquals(List.of(), run.execute().removals(), "an override added after the start was not read");

        overrides("overrides:\n  - id: one\n    hashes: [kept]\n    until: 2020-01-01\n");
        run.execute();

        assertEquals(List.of("kept"), transmission.removed());
        assertGone(file);
    }

    @Test
    void endsFailedAndRemovesNothingWhenTheOverridesFileCannotBeRead() throws IOException {
        Path due = file(downloads.resolve("Due/movie.mkv"));
        transmission.add(torrent("due", 30 * DAY, "Due/movie.mkv"));
        overrides("overrides: [\n");

        RunOutcome outcome = run();

        assertEquals(Status.FAILED, outcome.status());
        assertTrue(
                outcome.error().orElseThrow().startsWith("The overrides file " + overridesFile() + " can't be read: "),
                outcome.error().orElseThrow());
        assertEquals(List.of(), outcome.verdicts());
        assertEquals(List.of(), transmission.removed());
        assertTrue(Files.exists(due));
    }

    private Path overridesFile() {
        return root.resolve("overrides.yaml");
    }

    private void overrides(String yaml) throws IOException {
        Files.writeString(overridesFile(), yaml);
    }

    @Test
    void anUnregisteredMemberLeavesAloneAndTheRestOfItsGroupStaysWithItsData() throws IOException {
        Path original = file(downloads.resolve("Original/movie.mkv"));
        Path link = downloads.resolve("cross-seed/movie.mkv");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, original);
        transmission.add(
                torrent("original", 29 * DAY, "Original/movie.mkv"),
                unregistered(torrent("cross-seed", DAY, "cross-seed/movie.mkv")));

        RunOutcome outcome = run();

        assertEquals(List.of("cross-seed"), transmission.removed());
        assertEquals(List.of("original"), transmission.hashes());
        assertGone(link, Objects.requireNonNull(link.getParent()));
        assertTrue(Files.exists(original), "the data of the member that stays was deleted");
        assertEquals(
                List.of(Reason.MINIMUM_NOT_REACHED, Reason.UNREGISTERED),
                outcome.verdicts().stream().map(verdict -> verdict.reason()).toList());
        assertEquals(1, outcome.removals().size());
        Removal removal = outcome.removals().getFirst();
        assertEquals(Reason.UNREGISTERED, removal.reason());
        assertEquals(
                List.of("cross-seed"),
                removal.removed().stream().map(Torrent::hash).toList());
        assertEquals(0, removal.deleted().spaceFreed());
    }

    @Test
    void anUnregisteredOriginalLeavesButItsDataStaysForTheMemberThatLinksToIt() throws IOException {
        Path original = file(downloads.resolve("Original/movie.mkv"));
        Path link = downloads.resolve("cross-seed/movie.mkv");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, original);
        transmission.add(
                unregistered(torrent("original", DAY, "Original/movie.mkv")),
                torrent("cross-seed", 29 * DAY, "cross-seed/movie.mkv"));

        RunOutcome outcome = run();

        assertEquals(List.of("original"), transmission.removed());
        assertEquals(List.of("cross-seed"), transmission.hashes());
        assertTrue(Files.exists(original), "the data the remaining member links to was deleted");
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(List.of(), outcome.removals().getFirst().deleted().files());
    }

    @Test
    void anUnregisteredMemberAndTheRestOfItsGroupThatIsDueLeaveInOneRun() throws IOException {
        Path original = file(downloads.resolve("Original/movie.mkv"));
        Path link = downloads.resolve("cross-seed/movie.mkv");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, original);
        transmission.add(
                unregistered(torrent("original", DAY, "Original/movie.mkv")),
                torrent("cross-seed", 30 * DAY, "cross-seed/movie.mkv"));

        RunOutcome outcome = run();

        assertEquals(List.of("original", "cross-seed"), transmission.removed());
        assertGone(link);
        assertEquals(
                List.of(Reason.UNREGISTERED, Reason.ALL_MET),
                outcome.removals().stream().map(Removal::reason).toList());
        // Two removals: the data was still in use when its own torrent left, and the second removal deletes only
        // the paths of its own torrent. The data stays behind as an orphan.
        assertTrue(Files.exists(original));
        assertEquals(0, outcome.spaceFreed());
    }

    /** The same torrent, with the tracker error of a torrent its tracker has deleted. */
    private static Torrent unregistered(Torrent torrent) {
        return new Torrent(
                torrent.hash(),
                torrent.name(),
                torrent.status(),
                torrent.percentDone(),
                2,
                "Unregistered torrent",
                torrent.secondsSeeding(),
                torrent.isPrivate(),
                torrent.downloadDir(),
                torrent.trackers(),
                torrent.files());
    }

    private RunOutcome run() {
        return run(new OverrideStore(overridesFile())).execute();
    }

    private Run run(OverrideStore overrides) {
        return new Run(transmission, TRACKERS, downloads, List.of(library), ZONE, overrides);
    }

    /** A finished, seeding torrent of tracker-a in the downloads root. */
    private Torrent torrent(String hash, long secondsSeeding, String... files) {
        return new Torrent(
                hash,
                hash,
                Torrent.Status.SEEDING,
                1,
                0,
                "",
                secondsSeeding,
                true,
                downloads.toString(),
                List.of("https://tracker-a.example/announce"),
                Arrays.stream(files)
                        .map(name -> new Torrent.File(name, 4, true))
                        .toList());
    }

    private static void assertGone(Path... paths) {
        for (Path path : paths) {
            assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS), path + " is still there");
        }
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }
}
