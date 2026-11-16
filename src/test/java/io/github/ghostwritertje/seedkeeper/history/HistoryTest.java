package io.github.ghostwritertje.seedkeeper.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.decide.Tracker;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedMember;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedRemoval;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedRun;
import io.github.ghostwritertje.seedkeeper.run.Deletion.Deleted;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Removal;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Status;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** The run history in a real SQLite file, migrated with the migrations of the application. */
class HistoryTest {

    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final ZonedDateTime MONDAY = ZonedDateTime.of(2026, 11, 16, 6, 0, 0, 0, BRUSSELS);
    private static final Tracker TRACKER_A = new Tracker("tracker-a", Duration.ofDays(30));

    @TempDir
    Path dataDir;

    @Test
    void aRunIsListedWithWhatItRemoved() {
        History history = open();
        Torrent unregistered = new Torrent(
                "a1",
                "Gone.Movie",
                Torrent.Status.DOWNLOADING,
                0.5,
                2,
                "Unregistered torrent",
                0,
                true,
                "/downloads",
                List.of(),
                List.of());
        Torrent unmanaged = torrent("b2", "Other.Movie");
        Removal removal = new Removal(
                Reason.ALL_MET,
                List.of(unregistered, unmanaged),
                List.of(unregistered),
                Optional.of("Transmission refused"),
                new Deleted(
                        List.of(Path.of("/downloads/a"), Path.of("/downloads/b")),
                        1024,
                        List.of(Path.of("/elsewhere/c"))));
        GroupVerdict verdict = new GroupVerdict(
                List.of(member("a1", Optional.of(TRACKER_A)), member("b2", Optional.empty())),
                Reason.ALL_MET.verdict(),
                Reason.ALL_MET,
                0,
                1,
                Optional.empty());

        history.record(new RunOutcome(
                MONDAY, MONDAY.plusSeconds(4), Status.SUCCEEDED, Optional.empty(), List.of(verdict), List.of(removal)));

        RecordedRun run = history.runs(Optional.empty()).getFirst();
        assertEquals(MONDAY.toInstant(), run.started());
        assertEquals(MONDAY.plusSeconds(4).toInstant(), run.ended());
        assertEquals("succeeded", run.status());
        assertEquals(Optional.empty(), run.error());
        assertEquals(
                List.of(new RecordedRemoval(
                        run.id(),
                        MONDAY.toInstant(),
                        "all-met",
                        Optional.of("Transmission refused"),
                        1024,
                        2,
                        1,
                        List.of(
                                new RecordedMember(
                                        "a1",
                                        "Gone.Movie",
                                        Optional.of("tracker-a"),
                                        Optional.of("Unregistered torrent"),
                                        true,
                                        true),
                                new RecordedMember(
                                        "b2", "Other.Movie", Optional.empty(), Optional.empty(), false, false)))),
                run.removals());
        assertEquals(run.removals(), history.removals(Optional.empty()));
        assertEquals(0, run.groupsRemoved());
        assertEquals(1, run.torrentsRemoved());
        assertEquals(2, run.filesDeleted());
        assertEquals(1024, run.spaceFreed());
    }

    @Test
    void runsComeNewestFirstAndSinceLeavesOutTheOlderOnes() {
        History history = open();
        history.record(failed(MONDAY, "Transmission does not answer"));
        history.record(succeeded(MONDAY.plusDays(1)));
        history.record(succeeded(MONDAY.plusDays(2)));

        assertEquals(
                List.of(MONDAY.plusDays(2).toInstant(), MONDAY.plusDays(1).toInstant(), MONDAY.toInstant()),
                history.runs(Optional.empty()).stream()
                        .map(RecordedRun::started)
                        .toList());
        assertEquals(
                Optional.of("Transmission does not answer"),
                history.runs(Optional.empty()).getLast().error());
        assertEquals("failed", history.runs(Optional.empty()).getLast().status());
        assertEquals(
                List.of(MONDAY.plusDays(2).toInstant(), MONDAY.plusDays(1).toInstant()),
                history.runs(Optional.of(MONDAY.plusDays(1).toInstant())).stream()
                        .map(RecordedRun::started)
                        .toList());
        assertEquals(
                List.of(),
                history.runs(Optional.of(MONDAY.plusDays(2).plusSeconds(1).toInstant())));
    }

    @Test
    void runsAndRemovalsOlderThanAYearAreDeleted() {
        History history = open();
        Torrent torrent = torrent("a1", "Old.Movie");
        Removal removal = new Removal(
                Reason.ALL_MET,
                List.of(torrent),
                List.of(torrent),
                Optional.empty(),
                new Deleted(List.of(Path.of("/downloads/a")), 4, List.of()));
        history.record(new RunOutcome(
                MONDAY.minusYears(1).minusSeconds(1),
                MONDAY.minusYears(1),
                Status.SUCCEEDED,
                Optional.empty(),
                List.of(),
                List.of(removal)));
        history.record(new RunOutcome(
                MONDAY.minusYears(1),
                MONDAY.minusYears(1),
                Status.SUCCEEDED,
                Optional.empty(),
                List.of(),
                List.of(removal)));
        assertEquals(2, history.runs(Optional.empty()).size());

        history.record(succeeded(MONDAY));

        assertEquals(
                List.of(MONDAY.toInstant(), MONDAY.minusYears(1).toInstant()),
                history.runs(Optional.empty()).stream()
                        .map(RecordedRun::started)
                        .toList());
        assertEquals(
                List.of(MONDAY.minusYears(1).toInstant()),
                history.removals(Optional.empty()).stream()
                        .map(RecordedRemoval::started)
                        .toList());
    }

    @Test
    void reasonSinceStaysPutWhileTheReasonIsUnchangedAndResetsWhenItChanges() {
        History history = open();
        GroupVerdict minimum = group(Reason.MINIMUM_NOT_REACHED, "a1");
        GroupVerdict linked = group(Reason.LIBRARY_LINK, "a1");
        assertEquals(Optional.empty(), history.reasonsSince().of(minimum));

        history.record(succeeded(MONDAY, minimum));
        history.record(succeeded(MONDAY.plusDays(1), minimum));
        history.record(succeeded(MONDAY.plusDays(2), minimum));

        assertEquals(Optional.of(MONDAY.toInstant()), history.reasonsSince().of(minimum));
        assertEquals(
                Optional.empty(),
                history.reasonsSince().of(linked),
                "a group whose reason has changed since the last run has no date yet");

        history.record(succeeded(MONDAY.plusDays(3), linked));

        assertEquals(
                Optional.of(MONDAY.plusDays(3).toInstant()),
                history.reasonsSince().of(linked));

        history.record(succeeded(MONDAY.plusDays(4), minimum));

        assertEquals(
                Optional.of(MONDAY.plusDays(4).toInstant()),
                history.reasonsSince().of(minimum),
                "a reason that comes back starts anew");
    }

    @Test
    void aFailedRunLeavesReasonSinceAsItIs() {
        History history = open();
        GroupVerdict minimum = group(Reason.MINIMUM_NOT_REACHED, "a1");
        history.record(succeeded(MONDAY, minimum));

        history.record(failed(MONDAY.plusDays(1), "Transmission does not answer"));
        history.record(succeeded(MONDAY.plusDays(2), minimum));

        assertEquals(Optional.of(MONDAY.toInstant()), history.reasonsSince().of(minimum));
    }

    @Test
    void aGroupThatGainsOrLosesAMemberKeepsItsReasonSince() {
        History history = open();
        history.record(succeeded(MONDAY, group(Reason.MINIMUM_NOT_REACHED, "a1"), group(Reason.LIBRARY_LINK, "c3")));

        history.record(succeeded(
                MONDAY.plusDays(1), group(Reason.MINIMUM_NOT_REACHED, "a1", "b2"), group(Reason.LIBRARY_LINK, "c3")));

        assertEquals(
                Optional.of(MONDAY.toInstant()),
                history.reasonsSince().of(group(Reason.MINIMUM_NOT_REACHED, "a1", "b2")));
        assertEquals(
                Optional.of(MONDAY.toInstant()), history.reasonsSince().of(group(Reason.MINIMUM_NOT_REACHED, "b2")));
        assertEquals(Optional.of(MONDAY.toInstant()), history.reasonsSince().of(group(Reason.LIBRARY_LINK, "c3")));
    }

    @Test
    void aTorrentThatLeftAndCameBackStartsAnew() {
        History history = open();
        GroupVerdict minimum = group(Reason.MINIMUM_NOT_REACHED, "a1");
        history.record(succeeded(MONDAY, minimum));
        history.record(succeeded(MONDAY.plusDays(1)));

        history.record(succeeded(MONDAY.plusDays(2), minimum));

        assertEquals(
                Optional.of(MONDAY.plusDays(2).toInstant()),
                history.reasonsSince().of(minimum));
    }

    @Test
    void theHistoryIsKeptInTheFileAndAFileThatIsGoneIsMadeAnewEmpty() throws IOException {
        open().record(succeeded(MONDAY, group(Reason.LIBRARY_LINK, "a1")));
        assertEquals(1, open().runs(Optional.empty()).size(), "the history did not outlive the application");

        try (Stream<Path> files = Files.list(dataDir)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        History anew = open();

        assertEquals(List.of(), anew.runs(Optional.empty()));
        assertEquals(Optional.empty(), anew.reasonsSince().of(group(Reason.LIBRARY_LINK, "a1")));
        assertTrue(Files.exists(dataDir.resolve("seedkeeper.db")));
    }

    /** What the application does at start: migrate {@code seedkeeper.db} in the data dir. */
    private History open() {
        SQLiteDataSource database = new SQLiteDataSource();
        database.setUrl("jdbc:sqlite:" + dataDir.resolve("seedkeeper.db"));
        Flyway.configure().dataSource(database).load().migrate();
        return new History(database);
    }

    private static RunOutcome succeeded(ZonedDateTime started, GroupVerdict... verdicts) {
        return new RunOutcome(
                started, started.plusSeconds(1), Status.SUCCEEDED, Optional.empty(), List.of(verdicts), List.of());
    }

    private static RunOutcome failed(ZonedDateTime started, String error) {
        return new RunOutcome(started, started.plusSeconds(1), Status.FAILED, Optional.of(error), List.of(), List.of());
    }

    private static GroupVerdict group(Reason reason, String... hashes) {
        return new GroupVerdict(
                Stream.of(hashes)
                        .map(hash -> member(hash, Optional.of(TRACKER_A)))
                        .toList(),
                reason.verdict(),
                reason,
                0,
                1,
                Optional.empty());
    }

    private static GroupVerdict.Member member(String hash, Optional<Tracker> tracker) {
        return new GroupVerdict.Member(hash, hash, tracker, Duration.ofDays(1), Torrent.Status.SEEDING);
    }

    private static Torrent torrent(String hash, String name) {
        return new Torrent(hash, name, Torrent.Status.SEEDING, 1, 0, "", 0, true, "/downloads", List.of(), List.of());
    }
}
