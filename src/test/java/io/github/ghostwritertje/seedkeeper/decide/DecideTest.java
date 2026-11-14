package io.github.ghostwritertje.seedkeeper.decide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict.Member;
import io.github.ghostwritertje.seedkeeper.decide.TrackerRules.ManagedTracker;
import io.github.ghostwritertje.seedkeeper.snapshot.FileId;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.SnapshotTorrent;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.WantedFile;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The rules of spec §3.5 that exist so far, one row each. Every torrent is a group of its own. */
class DecideTest {

    private static final long DAY = 86_400;
    private static final String TRACKER_A = "https://tracker-a.example/announce";
    private static final String TRACKER_B = "https://tracker-b.example/announce";
    private static final String UNKNOWN = "https://elsewhere.example/announce";

    /** tracker-a: the default 30 days. tracker-b: 14 days. public: 1 day. */
    private static final TrackerRules RULES = new TrackerRules(List.of(
            new ManagedTracker("tracker-a", List.of("tracker-a.example"), Optional.empty()),
            new ManagedTracker("tracker-b", List.of("tracker-b.example"), Optional.of(Duration.ofDays(14)))),
            Duration.ofDays(30), Optional.of(Duration.ofDays(1)));

    private static final FileId IN_LIBRARY = new FileId(1, 100);
    private static final FileId ALSO_IN_LIBRARY = new FileId(1, 101);
    private static final FileId DOWNLOAD_ONLY = new FileId(1, 200);
    private static final Set<FileId> LIBRARY = Set.of(IN_LIBRARY, ALSO_IN_LIBRARY);

    /** A finished, seeding, private torrent of tracker-a with one wanted file that has no library link. */
    private static final class Case {

        private String announce = TRACKER_A;
        private boolean isPrivate = true;
        private double percentDone = 1.0;
        private Status status = Status.SEEDING;
        private long secondsSeeding = 0;
        private List<FileId> wanted = List.of(DOWNLOAD_ONLY);

        Case announce(String announce) {
            this.announce = announce;
            return this;
        }

        Case notPrivate() {
            this.isPrivate = false;
            return this;
        }

        Case percentDone(double percentDone, Status status) {
            this.percentDone = percentDone;
            this.status = status;
            return this;
        }

        Case seeded(long seconds) {
            this.secondsSeeding = seconds;
            return this;
        }

        Case wanted(FileId... files) {
            this.wanted = List.of(files);
            return this;
        }

        Snapshot snapshot() {
            List<WantedFile> files = new ArrayList<>();
            for (int i = 0; i < wanted.size(); i++) {
                files.add(new WantedFile("file-" + i, Optional.of(wanted.get(i))));
            }
            Torrent torrent = new Torrent("hash-1", "Torrent 1", status, percentDone, 0, "", secondsSeeding, isPrivate,
                    "/downloads", List.of(announce), List.of());
            return new Snapshot(List.of(new SnapshotTorrent(torrent, files)), LIBRARY);
        }
    }

    private static Case torrent() {
        return new Case();
    }

    static Stream<Arguments> rules() {
        return Stream.of(
                argumentSet("unmanaged: left alone",
                        torrent().announce(UNKNOWN).seeded(400 * DAY),
                        Verdict.LEFT_ALONE, Reason.UNMANAGED, 0, 1),
                argumentSet("unmanaged comes before not finished and library link",
                        torrent().announce(UNKNOWN).percentDone(0.5, Status.DOWNLOADING).wanted(IN_LIBRARY),
                        Verdict.LEFT_ALONE, Reason.UNMANAGED, 1, 1),
                argumentSet("not finished: keep",
                        torrent().percentDone(0.999, Status.DOWNLOADING),
                        Verdict.KEEP, Reason.NOT_FINISHED, 0, 1),
                argumentSet("not finished, stopped before finishing: keep",
                        torrent().percentDone(0.0, Status.STOPPED).seeded(400 * DAY),
                        Verdict.KEEP, Reason.NOT_FINISHED, 0, 1),
                argumentSet("not finished comes before library link",
                        torrent().percentDone(0.5, Status.DOWNLOADING).wanted(IN_LIBRARY),
                        Verdict.KEEP, Reason.NOT_FINISHED, 1, 1),
                argumentSet("library link on one of three files: keep",
                        torrent().seeded(400 * DAY).wanted(DOWNLOAD_ONLY, IN_LIBRARY, new FileId(1, 201)),
                        Verdict.KEEP, Reason.LIBRARY_LINK, 1, 3),
                argumentSet("library link on every file: keep",
                        torrent().seeded(400 * DAY).wanted(IN_LIBRARY, ALSO_IN_LIBRARY),
                        Verdict.KEEP, Reason.LIBRARY_LINK, 2, 2),
                argumentSet("library link comes before minimum not reached",
                        torrent().seeded(0).wanted(IN_LIBRARY),
                        Verdict.KEEP, Reason.LIBRARY_LINK, 1, 1),
                argumentSet("same inode number on another device is not a library link",
                        torrent().seeded(400 * DAY).wanted(new FileId(2, 100)),
                        Verdict.REMOVE, Reason.ALL_MET, 0, 1),
                argumentSet("minimum not reached, default minimum: keep",
                        torrent().seeded(30 * DAY - 1),
                        Verdict.KEEP, Reason.MINIMUM_NOT_REACHED, 0, 1),
                argumentSet("minimum not reached, the tracker's own minimum: keep",
                        torrent().announce(TRACKER_B).seeded(14 * DAY - 1),
                        Verdict.KEEP, Reason.MINIMUM_NOT_REACHED, 0, 1),
                argumentSet("minimum not reached, public: keep",
                        torrent().announce(UNKNOWN).notPrivate().seeded(DAY - 1),
                        Verdict.KEEP, Reason.MINIMUM_NOT_REACHED, 0, 1),
                argumentSet("all met at exactly the default minimum: remove",
                        torrent().seeded(30 * DAY),
                        Verdict.REMOVE, Reason.ALL_MET, 0, 1),
                argumentSet("all met at exactly the tracker's own minimum: remove",
                        torrent().announce(TRACKER_B).seeded(14 * DAY),
                        Verdict.REMOVE, Reason.ALL_MET, 0, 1),
                argumentSet("all met, public: remove",
                        torrent().announce(UNKNOWN).notPrivate().seeded(DAY),
                        Verdict.REMOVE, Reason.ALL_MET, 0, 1),
                argumentSet("all met while paused: remove",
                        torrent().percentDone(1.0, Status.STOPPED).seeded(30 * DAY),
                        Verdict.REMOVE, Reason.ALL_MET, 0, 1));
    }

    @ParameterizedTest
    @MethodSource("rules")
    void firstMatchingRuleWins(Case given, Verdict verdict, Reason reason, int libraryLinks, int files) {
        List<GroupVerdict> verdicts = Decide.decide(given.snapshot(), RULES);

        assertEquals(1, verdicts.size());
        GroupVerdict group = verdicts.get(0);
        assertEquals(verdict, group.verdict());
        assertEquals(reason, group.reason());
        assertEquals(libraryLinks, group.libraryLinks());
        assertEquals(files, group.files());
    }

    @Test
    void aMemberCarriesItsTrackerSeedTimeAndMinimum() {
        List<GroupVerdict> verdicts = Decide.decide(torrent().announce(TRACKER_B).seeded(3 * DAY).snapshot(), RULES);

        assertEquals(List.of(new Member("hash-1", "Torrent 1", Optional.of(new Tracker("tracker-b",
                Duration.ofDays(14))), Duration.ofDays(3), Status.SEEDING)), verdicts.get(0).members());
    }

    @Test
    void anUnmanagedMemberHasNoTracker() {
        List<GroupVerdict> verdicts = Decide.decide(torrent().announce(UNKNOWN).snapshot(), RULES);

        assertEquals(Optional.empty(), verdicts.get(0).members().get(0).tracker());
    }

    @Test
    void everyTorrentIsAGroupOfItsOwnInSnapshotOrder() {
        Snapshot first = torrent().seeded(30 * DAY).snapshot();
        Snapshot second = torrent().wanted(IN_LIBRARY).snapshot();
        Snapshot both = new Snapshot(
                Stream.concat(first.torrents().stream(), second.torrents().stream()).toList(), LIBRARY);

        List<GroupVerdict> verdicts = Decide.decide(both, RULES);

        assertEquals(List.of(Reason.ALL_MET, Reason.LIBRARY_LINK),
                verdicts.stream().map(GroupVerdict::reason).toList());
    }

    @Test
    void verdictsAndReasonsHaveKebabCaseCodes() {
        assertEquals(List.of("remove", "left-alone", "keep"), Stream.of(Verdict.values()).map(Verdict::code).toList());
        assertEquals(List.of("unmanaged", "not-finished", "library-link", "minimum-not-reached", "all-met"),
                Stream.of(Reason.values()).map(Reason::code).toList());
    }
}
