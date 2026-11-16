package io.github.ghostwritertje.seedkeeper.decide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict.Member;
import io.github.ghostwritertje.seedkeeper.decide.TrackerRules.ManagedTracker;
import io.github.ghostwritertje.seedkeeper.keep.KeepOverride;
import io.github.ghostwritertje.seedkeeper.snapshot.FileId;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.SnapshotTorrent;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.WantedFile;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent.Status;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The rules of spec §3.5 that exist so far and the forecast of §3.6, one row each. */
class DecideTest {

    private static final long DAY = 86_400;
    private static final String TRACKER_A = "https://tracker-a.example/announce";
    private static final String TRACKER_B = "https://tracker-b.example/announce";
    private static final String UNKNOWN = "https://elsewhere.example/announce";

    /** tracker-a: the default 30 days. tracker-b: 14 days. public: 1 day. */
    private static final TrackerRules RULES = new TrackerRules(
            List.of(
                    new ManagedTracker("tracker-a", List.of("tracker-a.example"), Optional.empty()),
                    new ManagedTracker("tracker-b", List.of("tracker-b.example"), Optional.of(Duration.ofDays(14)))),
            Duration.ofDays(30),
            Optional.of(Duration.ofDays(1)));

    private static final FileId IN_LIBRARY = new FileId(1, 100);
    private static final FileId ALSO_IN_LIBRARY = new FileId(1, 101);
    private static final FileId DOWNLOAD_ONLY = new FileId(1, 200);
    private static final FileId SHARED = new FileId(1, 300);
    private static final Set<FileId> LIBRARY = Set.of(IN_LIBRARY, ALSO_IN_LIBRARY);

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-15T10:00:00+01:00[Europe/Brussels]");

    /** A finished, seeding, private torrent of tracker-a with one wanted file that has no library link. */
    private static final class Case {

        private String hash = "hash-1";
        private String announce = TRACKER_A;
        private boolean isPrivate = true;
        private double percentDone = 1.0;
        private Status status = Status.SEEDING;
        private long secondsSeeding = 0;
        private List<FileId> wanted = List.of(DOWNLOAD_ONLY);
        private int missing = 0;
        private int error = 0;
        private String errorString = "";

        Case hash(String hash) {
            this.hash = hash;
            return this;
        }

        Case paused() {
            this.status = Status.STOPPED;
            return this;
        }

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

        /** Adds wanted files that resolve to nothing on disk. */
        Case missing(int files) {
            this.missing = files;
            return this;
        }

        /** Transmission's error number: 1 tracker warning, 2 tracker error, 3 local error. */
        Case error(int error) {
            this.error = error;
            return this;
        }

        /** A tracker error (error 2) with this tracker message. */
        Case trackerError(String message) {
            this.error = 2;
            this.errorString = message;
            return this;
        }

        /** The tracker has deleted the torrent. */
        Case unregistered() {
            return trackerError("Unregistered torrent");
        }

        Case status(Status status) {
            this.status = status;
            return this;
        }

        Snapshot snapshot() {
            return DecideTest.snapshot(List.of(this));
        }

        private SnapshotTorrent snapshotTorrent() {
            List<WantedFile> files = new ArrayList<>();
            for (int i = 0; i < wanted.size(); i++) {
                files.add(new WantedFile("file-" + i, Optional.of(wanted.get(i))));
            }
            for (int i = 0; i < missing; i++) {
                files.add(new WantedFile("missing-" + i, Optional.empty()));
            }
            Torrent torrent = new Torrent(
                    hash,
                    "Torrent 1",
                    status,
                    percentDone,
                    error,
                    errorString,
                    secondsSeeding,
                    isPrivate,
                    "/downloads",
                    List.of(announce),
                    List.of());
            return new SnapshotTorrent(torrent, files);
        }
    }

    private static Snapshot snapshot(List<Case> torrents) {
        return new Snapshot(torrents.stream().map(Case::snapshotTorrent).toList(), LIBRARY);
    }

    /** Two torrents that share a file: {@code a} of tracker-a (30 days) and {@code b} of tracker-b (14 days). */
    private static Case a() {
        return torrent().hash("a").wanted(SHARED);
    }

    private static Case b() {
        return torrent().hash("b").announce(TRACKER_B).wanted(SHARED);
    }

    private static Case torrent() {
        return new Case();
    }

    static Stream<Arguments> rules() {
        return Stream.of(
                argumentSet(
                        "unmanaged: left alone",
                        torrent().announce(UNKNOWN).seeded(400 * DAY),
                        Verdict.LEFT_ALONE,
                        Reason.UNMANAGED,
                        0,
                        1),
                argumentSet(
                        "unmanaged comes before not finished and library link",
                        torrent()
                                .announce(UNKNOWN)
                                .percentDone(0.5, Status.DOWNLOADING)
                                .wanted(IN_LIBRARY),
                        Verdict.LEFT_ALONE,
                        Reason.UNMANAGED,
                        1,
                        1),
                argumentSet(
                        "unmanaged comes before missing data",
                        torrent().announce(UNKNOWN).seeded(400 * DAY).missing(1),
                        Verdict.LEFT_ALONE,
                        Reason.UNMANAGED,
                        0,
                        2),
                argumentSet(
                        "missing data comes before local error",
                        torrent().seeded(400 * DAY).missing(1).error(3),
                        Verdict.LEFT_ALONE,
                        Reason.MISSING_DATA,
                        0,
                        2),
                argumentSet(
                        "missing data comes before verifying",
                        torrent().seeded(400 * DAY).missing(1).status(Status.CHECKING),
                        Verdict.LEFT_ALONE,
                        Reason.MISSING_DATA,
                        0,
                        2),
                argumentSet(
                        "local error: left alone",
                        torrent().seeded(400 * DAY).error(3),
                        Verdict.LEFT_ALONE,
                        Reason.LOCAL_ERROR,
                        0,
                        1),
                argumentSet(
                        "local error comes before verifying",
                        torrent().seeded(400 * DAY).error(3).status(Status.CHECK_WAIT),
                        Verdict.LEFT_ALONE,
                        Reason.LOCAL_ERROR,
                        0,
                        1),
                argumentSet(
                        "local error comes before not finished and library link",
                        torrent().percentDone(0.5, Status.STOPPED).error(3).wanted(IN_LIBRARY),
                        Verdict.LEFT_ALONE,
                        Reason.LOCAL_ERROR,
                        1,
                        1),
                argumentSet(
                        "tracker warning (error 1) is not an input",
                        torrent().seeded(30 * DAY).error(1),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        0,
                        1),
                argumentSet(
                        "tracker error (error 2) that is not unregistered is not an input",
                        torrent().seeded(30 * DAY).error(2),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        0,
                        1),
                argumentSet(
                        "verifying, queued to check: keep",
                        torrent().seeded(400 * DAY).status(Status.CHECK_WAIT),
                        Verdict.KEEP,
                        Reason.VERIFYING,
                        0,
                        1),
                argumentSet(
                        "verifying, checking: keep",
                        torrent().seeded(400 * DAY).status(Status.CHECKING),
                        Verdict.KEEP,
                        Reason.VERIFYING,
                        0,
                        1),
                argumentSet(
                        "verifying comes before not finished",
                        torrent().percentDone(0.5, Status.CHECKING),
                        Verdict.KEEP,
                        Reason.VERIFYING,
                        0,
                        1),
                argumentSet(
                        "verifying comes before library link and minimum not reached",
                        torrent().seeded(0).status(Status.CHECKING).wanted(IN_LIBRARY),
                        Verdict.KEEP,
                        Reason.VERIFYING,
                        1,
                        1),
                argumentSet(
                        "queued to seed is not verifying",
                        torrent().seeded(30 * DAY).status(Status.SEED_WAIT),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        0,
                        1),
                argumentSet(
                        "not finished: keep",
                        torrent().percentDone(0.999, Status.DOWNLOADING),
                        Verdict.KEEP,
                        Reason.NOT_FINISHED,
                        0,
                        1),
                argumentSet(
                        "not finished, stopped before finishing: keep",
                        torrent().percentDone(0.0, Status.STOPPED).seeded(400 * DAY),
                        Verdict.KEEP,
                        Reason.NOT_FINISHED,
                        0,
                        1),
                argumentSet(
                        "not finished comes before library link",
                        torrent().percentDone(0.5, Status.DOWNLOADING).wanted(IN_LIBRARY),
                        Verdict.KEEP,
                        Reason.NOT_FINISHED,
                        1,
                        1),
                argumentSet(
                        "library link on one of three files: keep",
                        torrent().seeded(400 * DAY).wanted(DOWNLOAD_ONLY, IN_LIBRARY, new FileId(1, 201)),
                        Verdict.KEEP,
                        Reason.LIBRARY_LINK,
                        1,
                        3),
                argumentSet(
                        "library link on every file: keep",
                        torrent().seeded(400 * DAY).wanted(IN_LIBRARY, ALSO_IN_LIBRARY),
                        Verdict.KEEP,
                        Reason.LIBRARY_LINK,
                        2,
                        2),
                argumentSet(
                        "library link comes before minimum not reached",
                        torrent().seeded(0).wanted(IN_LIBRARY),
                        Verdict.KEEP,
                        Reason.LIBRARY_LINK,
                        1,
                        1),
                argumentSet(
                        "same inode number on another device is not a library link",
                        torrent().seeded(400 * DAY).wanted(new FileId(2, 100)),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        0,
                        1),
                argumentSet(
                        "minimum not reached, default minimum: keep",
                        torrent().seeded(30 * DAY - 1),
                        Verdict.KEEP,
                        Reason.MINIMUM_NOT_REACHED,
                        0,
                        1),
                argumentSet(
                        "minimum not reached, the tracker's own minimum: keep",
                        torrent().announce(TRACKER_B).seeded(14 * DAY - 1),
                        Verdict.KEEP,
                        Reason.MINIMUM_NOT_REACHED,
                        0,
                        1),
                argumentSet(
                        "minimum not reached, public: keep",
                        torrent().announce(UNKNOWN).notPrivate().seeded(DAY - 1),
                        Verdict.KEEP,
                        Reason.MINIMUM_NOT_REACHED,
                        0,
                        1),
                argumentSet(
                        "all met at exactly the default minimum: remove",
                        torrent().seeded(30 * DAY),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        0,
                        1),
                argumentSet(
                        "all met at exactly the tracker's own minimum: remove",
                        torrent().announce(TRACKER_B).seeded(14 * DAY),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        0,
                        1),
                argumentSet(
                        "all met, public: remove",
                        torrent().announce(UNKNOWN).notPrivate().seeded(DAY),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        0,
                        1),
                argumentSet(
                        "all met while paused: remove",
                        torrent().percentDone(1.0, Status.STOPPED).seeded(30 * DAY),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        0,
                        1));
    }

    @ParameterizedTest
    @MethodSource("rules")
    void firstMatchingRuleWins(Case given, Verdict verdict, Reason reason, int libraryLinks, int files) {
        List<GroupVerdict> verdicts = Decide.decide(given.snapshot(), RULES, NOW);

        assertEquals(1, verdicts.size());
        GroupVerdict group = verdicts.get(0);
        assertEquals(verdict, group.verdict());
        assertEquals(reason, group.reason());
        assertEquals(libraryLinks, group.libraryLinks());
        assertEquals(files, group.files());
    }

    static Stream<Arguments> groupRules() {
        return Stream.of(
                argumentSet(
                        "every member has met its own tracker's minimum: remove",
                        List.of(a().seeded(30 * DAY), b().seeded(14 * DAY)),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        0,
                        1),
                argumentSet(
                        "the member with the shorter minimum is below it: keep",
                        List.of(a().seeded(400 * DAY), b().seeded(14 * DAY - 1)),
                        Verdict.KEEP,
                        Reason.MINIMUM_NOT_REACHED,
                        0,
                        1),
                argumentSet(
                        "the member with the longer minimum is below it: keep",
                        List.of(a().seeded(30 * DAY - 1), b().seeded(400 * DAY)),
                        Verdict.KEEP,
                        Reason.MINIMUM_NOT_REACHED,
                        0,
                        1),
                argumentSet(
                        "the same seed time meets one tracker's minimum and not the other's: keep",
                        List.of(a().seeded(20 * DAY), b().seeded(20 * DAY)),
                        Verdict.KEEP,
                        Reason.MINIMUM_NOT_REACHED,
                        0,
                        1),
                argumentSet(
                        "a library link on a file only one member has keeps the group",
                        List.of(a().seeded(400 * DAY), b().seeded(400 * DAY).wanted(SHARED, IN_LIBRARY)),
                        Verdict.KEEP,
                        Reason.LIBRARY_LINK,
                        1,
                        2),
                argumentSet(
                        "a library link on the shared file counts once",
                        List.of(
                                a().seeded(400 * DAY).wanted(IN_LIBRARY, DOWNLOAD_ONLY),
                                b().seeded(400 * DAY).wanted(IN_LIBRARY)),
                        Verdict.KEEP,
                        Reason.LIBRARY_LINK,
                        1,
                        2),
                argumentSet(
                        "one unmanaged member: the group is left alone",
                        List.of(a().seeded(400 * DAY), b().announce(UNKNOWN).seeded(400 * DAY)),
                        Verdict.LEFT_ALONE,
                        Reason.UNMANAGED,
                        0,
                        1),
                argumentSet(
                        "unmanaged comes before another member's library link",
                        List.of(a().wanted(SHARED, IN_LIBRARY), b().announce(UNKNOWN)),
                        Verdict.LEFT_ALONE,
                        Reason.UNMANAGED,
                        1,
                        2),
                argumentSet(
                        "one member with a local error: the group is left alone",
                        List.of(a().seeded(400 * DAY), b().seeded(400 * DAY).error(3)),
                        Verdict.LEFT_ALONE,
                        Reason.LOCAL_ERROR,
                        0,
                        1),
                argumentSet(
                        "one member's local error comes before another member's library link",
                        List.of(
                                a().seeded(400 * DAY).wanted(SHARED, IN_LIBRARY),
                                b().seeded(400 * DAY).error(3)),
                        Verdict.LEFT_ALONE,
                        Reason.LOCAL_ERROR,
                        1,
                        2),
                argumentSet(
                        "one member verifying: keep",
                        List.of(a().seeded(400 * DAY).status(Status.CHECKING), b().seeded(400 * DAY)),
                        Verdict.KEEP,
                        Reason.VERIFYING,
                        0,
                        1),
                argumentSet(
                        "one member unmanaged comes before another member verifying",
                        List.of(a().status(Status.CHECK_WAIT), b().announce(UNKNOWN)),
                        Verdict.LEFT_ALONE,
                        Reason.UNMANAGED,
                        0,
                        1),
                argumentSet(
                        "one member verifying comes before another member not finished",
                        List.of(a().percentDone(0.5, Status.DOWNLOADING), b().status(Status.CHECK_WAIT)),
                        Verdict.KEEP,
                        Reason.VERIFYING,
                        0,
                        1),
                argumentSet(
                        "one member not finished: keep",
                        List.of(a().seeded(400 * DAY), b().percentDone(0.5, Status.DOWNLOADING)),
                        Verdict.KEEP,
                        Reason.NOT_FINISHED,
                        0,
                        1),
                argumentSet(
                        "a paused member that has met its minimum does not keep the group",
                        List.of(a().seeded(30 * DAY).paused(), b().seeded(14 * DAY)),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        0,
                        1));
    }

    @ParameterizedTest
    @MethodSource("groupRules")
    void aGroupIsJudgedAsAWhole(List<Case> members, Verdict verdict, Reason reason, int libraryLinks, int files) {
        List<GroupVerdict> verdicts = Decide.decide(snapshot(members), RULES, NOW);

        assertEquals(1, verdicts.size());
        GroupVerdict group = verdicts.get(0);
        assertEquals(
                List.of("a", "b"), group.members().stream().map(Member::hash).toList());
        assertEquals(verdict, group.verdict());
        assertEquals(reason, group.reason());
        assertEquals(libraryLinks, group.libraryLinks());
        assertEquals(files, group.files());
    }

    static Stream<Arguments> missingData() {
        return Stream.of(
                argumentSet(
                        "a finished torrent with one of two files gone: left alone",
                        List.of(torrent().seeded(400 * DAY).missing(1)),
                        Reason.MISSING_DATA,
                        1,
                        2),
                argumentSet(
                        "a finished torrent with every file gone: left alone",
                        List.of(torrent().seeded(400 * DAY).wanted().missing(3)),
                        Reason.MISSING_DATA,
                        3,
                        3),
                argumentSet(
                        "a paused finished torrent with a file gone: left alone",
                        List.of(torrent().seeded(400 * DAY).paused().missing(1)),
                        Reason.MISSING_DATA,
                        1,
                        2),
                argumentSet(
                        "an unfinished torrent is never missing data: its files may not exist yet",
                        List.of(torrent()
                                .percentDone(0.5, Status.DOWNLOADING)
                                .wanted()
                                .missing(2)),
                        Reason.NOT_FINISHED,
                        0,
                        2),
                argumentSet(
                        "stopped before finishing with no file on disk: not missing data",
                        List.of(torrent()
                                .percentDone(0.0, Status.STOPPED)
                                .wanted()
                                .missing(1)),
                        Reason.NOT_FINISHED,
                        0,
                        1),
                argumentSet(
                        "every file on disk: not missing data",
                        List.of(torrent().seeded(400 * DAY)),
                        Reason.ALL_MET,
                        0,
                        1),
                argumentSet(
                        "one finished member of a group has a file gone: the group is left alone",
                        List.of(a().seeded(400 * DAY), b().seeded(400 * DAY).missing(1)),
                        Reason.MISSING_DATA,
                        1,
                        2),
                argumentSet(
                        "only the files of finished members count as missing",
                        List.of(
                                a().seeded(400 * DAY).missing(1),
                                b().percentDone(0.5, Status.DOWNLOADING).missing(2)),
                        Reason.MISSING_DATA,
                        1,
                        4));
    }

    @ParameterizedTest
    @MethodSource("missingData")
    void aFinishedMemberWithAFileGoneIsMissingData(List<Case> members, Reason reason, int missingFiles, int files) {
        GroupVerdict group = Decide.decide(snapshot(members), RULES, NOW).get(0);

        assertEquals(reason, group.reason());
        assertEquals(reason.verdict(), group.verdict());
        assertEquals(missingFiles, group.missingFiles());
        assertEquals(files, group.files());
    }

    static Stream<Arguments> secondaryReasons() {
        return Stream.of(
                argumentSet(
                        "all met: nothing else holds", List.of(torrent().seeded(30 * DAY)), Reason.ALL_MET, List.of()),
                argumentSet(
                        "only the minimum is not reached: no secondary reason",
                        List.of(torrent().seeded(0)),
                        Reason.MINIMUM_NOT_REACHED,
                        List.of()),
                argumentSet(
                        "library link, and the minimum is not reached either",
                        List.of(torrent().seeded(0).wanted(IN_LIBRARY)),
                        Reason.LIBRARY_LINK,
                        List.of(Reason.MINIMUM_NOT_REACHED)),
                argumentSet(
                        "library link, and the minimum is reached",
                        List.of(torrent().seeded(30 * DAY).wanted(IN_LIBRARY)),
                        Reason.LIBRARY_LINK,
                        List.of()),
                argumentSet(
                        "not finished, with a library link and no seed time",
                        List.of(torrent().percentDone(0.5, Status.DOWNLOADING).wanted(IN_LIBRARY)),
                        Reason.NOT_FINISHED,
                        List.of(Reason.LIBRARY_LINK, Reason.MINIMUM_NOT_REACHED)),
                argumentSet(
                        "verifying while not finished",
                        List.of(torrent().seeded(30 * DAY).percentDone(0.5, Status.CHECKING)),
                        Reason.VERIFYING,
                        List.of(Reason.NOT_FINISHED)),
                argumentSet(
                        "local error while verifying, with a library link",
                        List.of(torrent()
                                .seeded(30 * DAY)
                                .error(3)
                                .status(Status.CHECK_WAIT)
                                .wanted(IN_LIBRARY)),
                        Reason.LOCAL_ERROR,
                        List.of(Reason.VERIFYING, Reason.LIBRARY_LINK)),
                argumentSet(
                        "missing data with a local error",
                        List.of(torrent().seeded(30 * DAY).missing(1).error(3)),
                        Reason.MISSING_DATA,
                        List.of(Reason.LOCAL_ERROR)),
                argumentSet(
                        "unmanaged, and its seed time plays no part",
                        List.of(torrent().announce(UNKNOWN).seeded(0)),
                        Reason.UNMANAGED,
                        List.of()),
                argumentSet(
                        "everything at once, in the order of precedence",
                        List.of(
                                a().announce(UNKNOWN).wanted(SHARED, IN_LIBRARY),
                                b().seeded(0).missing(1).error(3),
                                torrent().hash("c").wanted(SHARED).status(Status.CHECKING),
                                torrent().hash("d").wanted(SHARED).percentDone(0.5, Status.DOWNLOADING)),
                        Reason.UNMANAGED,
                        List.of(
                                Reason.MISSING_DATA,
                                Reason.LOCAL_ERROR,
                                Reason.VERIFYING,
                                Reason.NOT_FINISHED,
                                Reason.LIBRARY_LINK,
                                Reason.MINIMUM_NOT_REACHED)),
                argumentSet(
                        "reasons of different members add up",
                        List.of(a().seeded(400 * DAY).status(Status.CHECKING), b().seeded(0)),
                        Reason.VERIFYING,
                        List.of(Reason.MINIMUM_NOT_REACHED)),
                argumentSet(
                        "paused and a tracker error are no reasons",
                        List.of(
                                a().seeded(0).paused().error(2),
                                b().seeded(400 * DAY).error(1)),
                        Reason.MINIMUM_NOT_REACHED,
                        List.of()));
    }

    @ParameterizedTest
    @MethodSource("secondaryReasons")
    void everyOtherReasonThatHoldsIsASecondaryReason(List<Case> members, Reason reason, List<Reason> secondary) {
        List<GroupVerdict> verdicts = Decide.decide(snapshot(members), RULES, NOW);

        assertEquals(1, verdicts.size());
        assertEquals(reason, verdicts.get(0).reason());
        assertEquals(secondary, verdicts.get(0).secondaryReasons());
    }

    @Test
    void twoRunsOnTheSameSnapshotGiveTheSameVerdicts() {
        Snapshot snapshot = snapshot(List.of(
                a().seeded(20 * DAY),
                b().seeded(13 * DAY).paused(),
                torrent().hash("c").announce(UNKNOWN).wanted(IN_LIBRARY),
                torrent().hash("d").seeded(400 * DAY).wanted().missing(2).error(3),
                torrent().hash("e").wanted(new FileId(1, 201)).status(Status.CHECKING),
                torrent().hash("f").wanted(new FileId(1, 202)).percentDone(0.5, Status.DOWNLOADING),
                torrent().hash("g").wanted(new FileId(1, 203)).seeded(30 * DAY)));

        List<GroupVerdict> first = Decide.decide(snapshot, RULES, NOW);
        List<GroupVerdict> second = Decide.decide(snapshot, RULES, NOW);

        assertEquals(
                List.of(
                        Reason.MINIMUM_NOT_REACHED,
                        Reason.UNMANAGED,
                        Reason.MISSING_DATA,
                        Reason.VERIFYING,
                        Reason.NOT_FINISHED,
                        Reason.ALL_MET),
                first.stream().map(GroupVerdict::reason).toList());
        assertEquals(first, second);
    }

    static Stream<Arguments> unregistered() {
        return Stream.of(
                argumentSet(
                        "unregistered: remove, whatever its seed time",
                        torrent().unregistered().seeded(0),
                        Verdict.REMOVE,
                        Reason.UNREGISTERED,
                        List.of(Reason.MINIMUM_NOT_REACHED)),
                argumentSet(
                        "unregistered with a library link: remove",
                        torrent().unregistered().seeded(400 * DAY).wanted(IN_LIBRARY),
                        Verdict.REMOVE,
                        Reason.UNREGISTERED,
                        List.of(Reason.LIBRARY_LINK)),
                argumentSet(
                        "unregistered comes before missing data and not finished",
                        torrent().unregistered().missing(1).percentDone(0.5, Status.STOPPED),
                        Verdict.REMOVE,
                        Reason.UNREGISTERED,
                        List.of(Reason.NOT_FINISHED, Reason.MINIMUM_NOT_REACHED)),
                argumentSet(
                        "unregistered but unmanaged: left alone, and unregistered stays visible",
                        torrent().unregistered().announce(UNKNOWN).missing(1).percentDone(0.5, Status.STOPPED),
                        Verdict.LEFT_ALONE,
                        Reason.UNMANAGED,
                        List.of(Reason.UNREGISTERED, Reason.NOT_FINISHED)),
                argumentSet(
                        "unregistered on public: remove",
                        torrent().unregistered().announce(UNKNOWN).notPrivate().seeded(0),
                        Verdict.REMOVE,
                        Reason.UNREGISTERED,
                        List.of(Reason.MINIMUM_NOT_REACHED)),
                argumentSet(
                        "unregistered but verifying: keep until the check is over",
                        torrent().unregistered().seeded(400 * DAY).status(Status.CHECKING),
                        Verdict.KEEP,
                        Reason.VERIFYING,
                        List.of()),
                argumentSet(
                        "unregistered but queued to verify: keep until the check is over",
                        torrent().unregistered().seeded(400 * DAY).status(Status.CHECK_WAIT),
                        Verdict.KEEP,
                        Reason.VERIFYING,
                        List.of()),
                argumentSet(
                        "unregistered and paused: remove",
                        torrent().unregistered().seeded(400 * DAY).paused(),
                        Verdict.REMOVE,
                        Reason.UNREGISTERED,
                        List.of()),
                argumentSet(
                        "a passkey problem is a tracker error: not an input",
                        torrent().trackerError("Passkey does not exist!").seeded(30 * DAY),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        List.of()),
                argumentSet(
                        "a tracker error that matches no pattern: not an input",
                        torrent().trackerError("Connection timed out").seeded(30 * DAY),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        List.of()));
    }

    @ParameterizedTest
    @MethodSource("unregistered")
    void anUnregisteredTorrentOfAManagedTrackerIsRemovedStraightAway(
            Case given, Verdict verdict, Reason reason, List<Reason> secondary) {
        List<GroupVerdict> verdicts = Decide.decide(given.snapshot(), RULES, NOW);

        assertEquals(1, verdicts.size());
        assertEquals(verdict, verdicts.get(0).verdict());
        assertEquals(reason, verdicts.get(0).reason());
        assertEquals(secondary, verdicts.get(0).secondaryReasons());
        assertEquals(NO_FORECAST, verdicts.get(0).forecast());
    }

    /** What one verdict says, short enough to compare a whole list: its members' hashes, verdict and reason. */
    private record Said(List<String> members, Verdict verdict, Reason reason) {}

    private static Said said(Verdict verdict, Reason reason, String... members) {
        return new Said(List.of(members), verdict, reason);
    }

    static Stream<Arguments> unregisteredMembers() {
        Case bridge = torrent().hash("u").wanted(SHARED, DOWNLOAD_ONLY);
        Case c = torrent().hash("c").wanted(DOWNLOAD_ONLY);
        return Stream.of(
                argumentSet(
                        "the unregistered member leaves; the rest has not met its minimum: keep",
                        List.of(a().unregistered().seeded(400 * DAY), b().seeded(14 * DAY - 1)),
                        List.of(
                                said(Verdict.REMOVE, Reason.UNREGISTERED, "a"),
                                said(Verdict.KEEP, Reason.MINIMUM_NOT_REACHED, "b"))),
                argumentSet(
                        "the unregistered member leaves; the rest has met its minimum: remove",
                        List.of(a().seeded(30 * DAY), b().unregistered().seeded(0)),
                        List.of(
                                said(Verdict.REMOVE, Reason.ALL_MET, "a"),
                                said(Verdict.REMOVE, Reason.UNREGISTERED, "b"))),
                argumentSet(
                        "what holds for the unregistered member only does not count for the rest",
                        List.of(a().unregistered().percentDone(0.5, Status.STOPPED), b().seeded(14 * DAY)),
                        List.of(
                                said(Verdict.REMOVE, Reason.UNREGISTERED, "a"),
                                said(Verdict.REMOVE, Reason.ALL_MET, "b"))),
                argumentSet(
                        "an unregistered member that is unmanaged stays, and the group is left alone",
                        List.of(a().unregistered().announce(UNKNOWN).seeded(400 * DAY), b().seeded(14 * DAY)),
                        List.of(said(Verdict.LEFT_ALONE, Reason.UNMANAGED, "a", "b"))),
                argumentSet(
                        "the rest is kept for its library link, the unregistered member still leaves",
                        List.of(
                                a().unregistered().wanted(IN_LIBRARY),
                                b().seeded(14 * DAY).wanted(IN_LIBRARY)),
                        List.of(
                                said(Verdict.REMOVE, Reason.UNREGISTERED, "a"),
                                said(Verdict.KEEP, Reason.LIBRARY_LINK, "b"))),
                argumentSet(
                        "an unregistered member that is verifying stays, and keeps the group",
                        List.of(a().unregistered().seeded(400 * DAY).status(Status.CHECKING), b().seeded(14 * DAY)),
                        List.of(said(Verdict.KEEP, Reason.VERIFYING, "a", "b"))),
                argumentSet(
                        "every member unregistered: each leaves on its own",
                        List.of(a().unregistered(), b().unregistered()),
                        List.of(
                                said(Verdict.REMOVE, Reason.UNREGISTERED, "a"),
                                said(Verdict.REMOVE, Reason.UNREGISTERED, "b"))),
                argumentSet(
                        "a member with a tracker error that is not unregistered stays in its group",
                        List.of(a().trackerError("Passkey does not exist!").seeded(30 * DAY), b().seeded(14 * DAY)),
                        List.of(said(Verdict.REMOVE, Reason.ALL_MET, "a", "b"))),
                argumentSet(
                        "members that only the unregistered member held together are two groups without it",
                        List.of(a().seeded(30 * DAY), bridge.unregistered(), c.seeded(0)),
                        List.of(
                                said(Verdict.REMOVE, Reason.ALL_MET, "a"),
                                said(Verdict.REMOVE, Reason.UNREGISTERED, "u"),
                                said(Verdict.KEEP, Reason.MINIMUM_NOT_REACHED, "c"))));
    }

    @ParameterizedTest
    @MethodSource("unregisteredMembers")
    void anUnregisteredMemberLeavesItsGroupAndTheRestIsJudgedWithoutIt(List<Case> members, List<Said> said) {
        List<GroupVerdict> verdicts = Decide.decide(snapshot(members), RULES, NOW);

        assertEquals(
                said,
                verdicts.stream()
                        .map(group -> new Said(
                                group.members().stream().map(Member::hash).toList(), group.verdict(), group.reason()))
                        .toList());
    }

    @Test
    void theRestOfTheGroupIsForecastWithoutTheUnregisteredMember() {
        List<GroupVerdict> verdicts =
                Decide.decide(snapshot(List.of(a().unregistered().seeded(0), b().seeded(10 * DAY))), RULES, NOW);

        assertEquals(
                List.of(NO_FORECAST, on("2026-11-19T10:00:00+01:00[Europe/Brussels]")),
                verdicts.stream().map(GroupVerdict::forecast).toList());
    }

    @Test
    void aMemberCarriesWhatItsTrackerReports() {
        List<GroupVerdict> verdicts = Decide.decide(
                snapshot(List.of(
                        a().unregistered().status(Status.CHECKING),
                        b().trackerError("Passkey does not exist!"),
                        torrent().hash("c").wanted(SHARED).error(1),
                        torrent().hash("d").wanted(SHARED).error(3),
                        torrent().hash("e").wanted(SHARED))),
                RULES,
                NOW);

        assertEquals(
                List.of(
                        Optional.of(new TrackerError(TrackerError.Kind.UNREGISTERED, "Unregistered torrent")),
                        Optional.of(new TrackerError(TrackerError.Kind.TRACKER_ERROR, "Passkey does not exist!")),
                        Optional.of(new TrackerError(TrackerError.Kind.TRACKER_WARNING, "")),
                        Optional.empty(),
                        Optional.empty()),
                verdicts.get(0).members().stream().map(Member::trackerError).toList());
    }

    private static final Optional<Forecast> CLOCK_STOPPED = Optional.of(new Forecast(Optional.empty()));
    private static final Optional<Forecast> NO_FORECAST = Optional.empty();

    private static Optional<Forecast> on(String date) {
        return Optional.of(new Forecast(Optional.of(ZonedDateTime.parse(date))));
    }

    static Stream<Arguments> forecasts() {
        return Stream.of(
                argumentSet(
                        "one member, 10 days to go",
                        List.of(torrent().seeded(20 * DAY)),
                        NOW,
                        on("2026-11-25T10:00:00+01:00[Europe/Brussels]")),
                argumentSet(
                        "one second to go",
                        List.of(torrent().seeded(30 * DAY - 1)),
                        NOW,
                        on("2026-11-15T10:00:01+01:00[Europe/Brussels]")),
                argumentSet(
                        "the member of the tracker with the longer minimum is the latest",
                        List.of(a().seeded(20 * DAY), b().seeded(13 * DAY)),
                        NOW,
                        on("2026-11-25T10:00:00+01:00[Europe/Brussels]")),
                argumentSet(
                        "the member of the tracker with the shorter minimum is the latest",
                        List.of(a().seeded(29 * DAY), b().seeded(2 * DAY)),
                        NOW,
                        on("2026-11-27T10:00:00+01:00[Europe/Brussels]")),
                argumentSet(
                        "a member that has met its minimum does not count",
                        List.of(a().seeded(400 * DAY), b().seeded(13 * DAY + DAY / 2)),
                        NOW,
                        on("2026-11-15T22:00:00+01:00[Europe/Brussels]")),
                argumentSet(
                        "the latest member is paused: clock stopped",
                        List.of(a().seeded(20 * DAY).paused(), b().seeded(13 * DAY)),
                        NOW,
                        CLOCK_STOPPED),
                argumentSet(
                        "the only member is paused: clock stopped",
                        List.of(torrent().seeded(20 * DAY).paused()),
                        NOW,
                        CLOCK_STOPPED),
                argumentSet(
                        "a paused member that is not the latest does not stop the clock",
                        List.of(a().seeded(20 * DAY), b().seeded(13 * DAY).paused()),
                        NOW,
                        on("2026-11-25T10:00:00+01:00[Europe/Brussels]")),
                argumentSet(
                        "a paused member that has met its minimum does not stop the clock",
                        List.of(a().seeded(20 * DAY), b().seeded(14 * DAY).paused()),
                        NOW,
                        on("2026-11-25T10:00:00+01:00[Europe/Brussels]")),
                argumentSet(
                        "two members are the latest and one of them is paused: clock stopped",
                        List.of(a().seeded(20 * DAY), b().seeded(4 * DAY).paused()),
                        NOW,
                        CLOCK_STOPPED),
                argumentSet(
                        "the date is in the time zone of now, across the change to summer time",
                        List.of(torrent().seeded(20 * DAY)),
                        ZonedDateTime.parse("2027-03-20T10:00:00+01:00[Europe/Brussels]"),
                        on("2027-03-30T11:00:00+02:00[Europe/Brussels]")),
                argumentSet(
                        "the date is in the time zone of now, another zone",
                        List.of(torrent().seeded(20 * DAY)),
                        ZonedDateTime.parse("2026-11-15T04:00:00-05:00[America/New_York]"),
                        on("2026-11-25T04:00:00-05:00[America/New_York]")),
                argumentSet(
                        "library link: no forecast, though the minimum is not reached either",
                        List.of(torrent().seeded(20 * DAY).wanted(IN_LIBRARY)),
                        NOW,
                        NO_FORECAST),
                argumentSet(
                        "not finished: no forecast",
                        List.of(torrent().percentDone(0.5, Status.DOWNLOADING)),
                        NOW,
                        NO_FORECAST),
                argumentSet("unmanaged: no forecast", List.of(torrent().announce(UNKNOWN)), NOW, NO_FORECAST),
                argumentSet("all met: no forecast", List.of(torrent().seeded(30 * DAY)), NOW, NO_FORECAST));
    }

    @ParameterizedTest
    @MethodSource("forecasts")
    void minimumNotReachedCarriesAForecast(List<Case> members, ZonedDateTime now, Optional<Forecast> forecast) {
        List<GroupVerdict> verdicts = Decide.decide(snapshot(members), RULES, now);

        assertEquals(1, verdicts.size());
        assertEquals(forecast, verdicts.get(0).forecast());
    }

    /** An override on hash {@code a}; {@code until} is its inclusive end date, null for none. */
    private static KeepOverride override(String until, String... hashes) {
        return new KeepOverride(
                "id-1",
                List.of(hashes),
                "Rewatching",
                Optional.ofNullable(until).map(LocalDate::parse),
                Optional.empty());
    }

    private static Optional<Forecast> until(String date) {
        return Optional.of(Forecast.until(LocalDate.parse(date)));
    }

    /** Now is 2026-11-15 10:00 in Brussels. The group is {@code a} and {@code b}, due for removal unless said. */
    static Stream<Arguments> overrides() {
        List<Case> due = List.of(a().seeded(30 * DAY), b().seeded(14 * DAY));
        return Stream.of(
                argumentSet(
                        "an override without an end date keeps a group that is due",
                        due,
                        List.of(override(null, "a", "b")),
                        NOW,
                        Verdict.KEEP,
                        Reason.KEEP_OVERRIDE,
                        List.of(),
                        NO_FORECAST),
                argumentSet(
                        "an override with one of the group's hashes matches the whole group",
                        due,
                        List.of(override(null, "gone", "b")),
                        NOW,
                        Verdict.KEEP,
                        Reason.KEEP_OVERRIDE,
                        List.of(),
                        NO_FORECAST),
                argumentSet(
                        "an override with an end date in the future keeps and shows the date",
                        due,
                        List.of(override("2027-03-01", "a")),
                        NOW,
                        Verdict.KEEP,
                        Reason.KEEP_OVERRIDE,
                        List.of(),
                        until("2027-03-01")),
                argumentSet(
                        "on the end date itself the override still keeps",
                        due,
                        List.of(override("2026-11-15", "a")),
                        NOW,
                        Verdict.KEEP,
                        Reason.KEEP_OVERRIDE,
                        List.of(),
                        until("2026-11-15")),
                argumentSet(
                        "the last second of the end date still keeps",
                        due,
                        List.of(override("2026-11-15", "a")),
                        ZonedDateTime.parse("2026-11-15T23:59:59+01:00[Europe/Brussels]"),
                        Verdict.KEEP,
                        Reason.KEEP_OVERRIDE,
                        List.of(),
                        until("2026-11-15")),
                argumentSet(
                        "the day after the end date the override has expired: no effect",
                        due,
                        List.of(override("2026-11-14", "a")),
                        NOW,
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        List.of(),
                        NO_FORECAST),
                argumentSet(
                        "the end date is in the time zone of now: still the 15th in New York",
                        due,
                        List.of(override("2026-11-15", "a")),
                        ZonedDateTime.parse("2026-11-15T23:00:00-05:00[America/New_York]"),
                        Verdict.KEEP,
                        Reason.KEEP_OVERRIDE,
                        List.of(),
                        until("2026-11-15")),
                argumentSet(
                        "the end date is in the time zone of now: already the 16th in Brussels",
                        due,
                        List.of(override("2026-11-15", "a")),
                        ZonedDateTime.parse("2026-11-16T05:00:00+01:00[Europe/Brussels]"),
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        List.of(),
                        NO_FORECAST),
                argumentSet(
                        "a stale override has no effect",
                        due,
                        List.of(override(null, "gone")),
                        NOW,
                        Verdict.REMOVE,
                        Reason.ALL_MET,
                        List.of(),
                        NO_FORECAST),
                argumentSet(
                        "an expired override next to an active one: the active one keeps",
                        due,
                        List.of(override("2026-01-01", "a"), override("2026-12-01", "b")),
                        NOW,
                        Verdict.KEEP,
                        Reason.KEEP_OVERRIDE,
                        List.of(),
                        until("2026-12-01")),
                argumentSet(
                        "two active overrides: the one that keeps longest shows",
                        due,
                        List.of(override("2027-01-01", "a"), override("2026-12-01", "b")),
                        NOW,
                        Verdict.KEEP,
                        Reason.KEEP_OVERRIDE,
                        List.of(),
                        until("2027-01-01")),
                argumentSet(
                        "two active overrides, one without an end date: no date",
                        due,
                        List.of(override("2027-01-01", "a"), override(null, "b")),
                        NOW,
                        Verdict.KEEP,
                        Reason.KEEP_OVERRIDE,
                        List.of(),
                        NO_FORECAST),
                argumentSet(
                        "an override comes before a library link and the minimum",
                        List.of(a().seeded(DAY).wanted(SHARED, IN_LIBRARY), b().seeded(DAY)),
                        List.of(override(null, "a")),
                        NOW,
                        Verdict.KEEP,
                        Reason.KEEP_OVERRIDE,
                        List.of(Reason.LIBRARY_LINK, Reason.MINIMUM_NOT_REACHED),
                        NO_FORECAST),
                argumentSet(
                        "not finished comes before an override",
                        List.of(a().percentDone(0.5, Status.DOWNLOADING), b()),
                        List.of(override("2027-03-01", "a")),
                        NOW,
                        Verdict.KEEP,
                        Reason.NOT_FINISHED,
                        List.of(Reason.KEEP_OVERRIDE, Reason.MINIMUM_NOT_REACHED),
                        NO_FORECAST),
                argumentSet(
                        "an override does not manage an unmanaged group",
                        List.of(a().seeded(400 * DAY), b().announce(UNKNOWN).seeded(400 * DAY)),
                        List.of(override(null, "a")),
                        NOW,
                        Verdict.LEFT_ALONE,
                        Reason.UNMANAGED,
                        List.of(Reason.KEEP_OVERRIDE),
                        NO_FORECAST),
                argumentSet(
                        "an expired override does not show as a secondary reason",
                        List.of(a().seeded(DAY), b().seeded(DAY)),
                        List.of(override("2026-11-14", "a")),
                        NOW,
                        Verdict.KEEP,
                        Reason.MINIMUM_NOT_REACHED,
                        List.of(),
                        on("2026-12-14T10:00:00+01:00[Europe/Brussels]")));
    }

    @ParameterizedTest
    @MethodSource("overrides")
    void aKeepOverrideOnlyPreventsARemoval(
            List<Case> members,
            List<KeepOverride> overrides,
            ZonedDateTime now,
            Verdict verdict,
            Reason reason,
            List<Reason> secondaryReasons,
            Optional<Forecast> forecast) {
        List<GroupVerdict> verdicts = Decide.decide(snapshot(members), RULES, overrides, now);

        assertEquals(1, verdicts.size());
        GroupVerdict group = verdicts.get(0);
        assertEquals(verdict, group.verdict());
        assertEquals(reason, group.reason());
        assertEquals(secondaryReasons, group.secondaryReasons());
        assertEquals(forecast, group.forecast());
    }

    /** Whatever the snapshot, adding overrides never turns a verdict into Remove. */
    @Test
    void anOverrideNeverCausesARemoval() {
        Snapshot snapshot = snapshot(List.of(
                a().seeded(20 * DAY),
                b().seeded(13 * DAY).paused(),
                torrent().hash("c").announce(UNKNOWN).wanted(IN_LIBRARY),
                torrent().hash("d").seeded(400 * DAY).wanted().missing(2).error(3),
                torrent().hash("e").wanted(new FileId(1, 201)).status(Status.CHECKING),
                torrent().hash("f").wanted(new FileId(1, 202)).percentDone(0.5, Status.DOWNLOADING),
                torrent().hash("g").wanted(new FileId(1, 203)).seeded(30 * DAY)));
        List<KeepOverride> overrides = List.of(
                override(null, "a"),
                override("2026-11-14", "b", "c"),
                override("2026-11-15", "d", "e"),
                override("2027-01-01", "f"),
                override(null, "gone"));

        List<GroupVerdict> without = Decide.decide(snapshot, RULES, List.of(), NOW);
        List<GroupVerdict> with = Decide.decide(snapshot, RULES, overrides, NOW);

        for (int i = 0; i < without.size(); i++) {
            if (with.get(i).verdict() == Verdict.REMOVE) {
                assertEquals(Verdict.REMOVE, without.get(i).verdict(), "an override caused the removal of group " + i);
            }
        }
        assertEquals(
                List.of(
                        Reason.KEEP_OVERRIDE,
                        Reason.UNMANAGED,
                        Reason.MISSING_DATA,
                        Reason.VERIFYING,
                        Reason.NOT_FINISHED,
                        Reason.ALL_MET),
                with.stream().map(GroupVerdict::reason).toList());
    }

    @Test
    void aMemberCarriesItsTrackerSeedTimeAndMinimum() {
        List<GroupVerdict> verdicts =
                Decide.decide(torrent().announce(TRACKER_B).seeded(3 * DAY).snapshot(), RULES, NOW);

        assertEquals(
                List.of(new Member(
                        "hash-1",
                        "Torrent 1",
                        Optional.of(new Tracker("tracker-b", Duration.ofDays(14))),
                        Duration.ofDays(3),
                        Status.SEEDING)),
                verdicts.get(0).members());
    }

    @Test
    void anUnmanagedMemberHasNoTracker() {
        List<GroupVerdict> verdicts = Decide.decide(torrent().announce(UNKNOWN).snapshot(), RULES, NOW);

        assertEquals(Optional.empty(), verdicts.get(0).members().get(0).tracker());
    }

    @Test
    void torrentsThatShareNoFileAreEachAGroupInSnapshotOrder() {
        Snapshot both = snapshot(
                List.of(torrent().seeded(30 * DAY), torrent().hash("hash-2").wanted(IN_LIBRARY)));

        List<GroupVerdict> verdicts = Decide.decide(both, RULES, NOW);

        assertEquals(
                List.of(Reason.ALL_MET, Reason.LIBRARY_LINK),
                verdicts.stream().map(GroupVerdict::reason).toList());
    }

    @Test
    void verdictsAndReasonsHaveKebabCaseCodes() {
        assertEquals(
                List.of("remove", "left-alone", "keep"),
                Stream.of(Verdict.values()).map(Verdict::code).toList());
        assertEquals(
                List.of(
                        "unregistered",
                        "unmanaged",
                        "missing-data",
                        "local-error",
                        "verifying",
                        "not-finished",
                        "keep-override",
                        "library-link",
                        "minimum-not-reached",
                        "all-met"),
                Stream.of(Reason.values()).map(Reason::code).toList());
    }
}
