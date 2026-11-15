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
    private static final TrackerRules RULES = new TrackerRules(List.of(
            new ManagedTracker("tracker-a", List.of("tracker-a.example"), Optional.empty()),
            new ManagedTracker("tracker-b", List.of("tracker-b.example"), Optional.of(Duration.ofDays(14)))),
            Duration.ofDays(30), Optional.of(Duration.ofDays(1)));

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

        Snapshot snapshot() {
            return DecideTest.snapshot(List.of(this));
        }

        private SnapshotTorrent snapshotTorrent() {
            List<WantedFile> files = new ArrayList<>();
            for (int i = 0; i < wanted.size(); i++) {
                files.add(new WantedFile("file-" + i, Optional.of(wanted.get(i))));
            }
            Torrent torrent = new Torrent(hash, "Torrent 1", status, percentDone, 0, "", secondsSeeding, isPrivate,
                    "/downloads", List.of(announce), List.of());
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
                argumentSet("every member has met its own tracker's minimum: remove",
                        List.of(a().seeded(30 * DAY), b().seeded(14 * DAY)),
                        Verdict.REMOVE, Reason.ALL_MET, 0, 1),
                argumentSet("the member with the shorter minimum is below it: keep",
                        List.of(a().seeded(400 * DAY), b().seeded(14 * DAY - 1)),
                        Verdict.KEEP, Reason.MINIMUM_NOT_REACHED, 0, 1),
                argumentSet("the member with the longer minimum is below it: keep",
                        List.of(a().seeded(30 * DAY - 1), b().seeded(400 * DAY)),
                        Verdict.KEEP, Reason.MINIMUM_NOT_REACHED, 0, 1),
                argumentSet("the same seed time meets one tracker's minimum and not the other's: keep",
                        List.of(a().seeded(20 * DAY), b().seeded(20 * DAY)),
                        Verdict.KEEP, Reason.MINIMUM_NOT_REACHED, 0, 1),
                argumentSet("a library link on a file only one member has keeps the group",
                        List.of(a().seeded(400 * DAY), b().seeded(400 * DAY).wanted(SHARED, IN_LIBRARY)),
                        Verdict.KEEP, Reason.LIBRARY_LINK, 1, 2),
                argumentSet("a library link on the shared file counts once",
                        List.of(a().seeded(400 * DAY).wanted(IN_LIBRARY, DOWNLOAD_ONLY),
                                b().seeded(400 * DAY).wanted(IN_LIBRARY)),
                        Verdict.KEEP, Reason.LIBRARY_LINK, 1, 2),
                argumentSet("one unmanaged member: the group is left alone",
                        List.of(a().seeded(400 * DAY), b().announce(UNKNOWN).seeded(400 * DAY)),
                        Verdict.LEFT_ALONE, Reason.UNMANAGED, 0, 1),
                argumentSet("unmanaged comes before another member's library link",
                        List.of(a().wanted(SHARED, IN_LIBRARY), b().announce(UNKNOWN)),
                        Verdict.LEFT_ALONE, Reason.UNMANAGED, 1, 2),
                argumentSet("one member not finished: keep",
                        List.of(a().seeded(400 * DAY), b().percentDone(0.5, Status.DOWNLOADING)),
                        Verdict.KEEP, Reason.NOT_FINISHED, 0, 1),
                argumentSet("a paused member that has met its minimum does not keep the group",
                        List.of(a().seeded(30 * DAY).paused(), b().seeded(14 * DAY)),
                        Verdict.REMOVE, Reason.ALL_MET, 0, 1));
    }

    @ParameterizedTest
    @MethodSource("groupRules")
    void aGroupIsJudgedAsAWhole(List<Case> members, Verdict verdict, Reason reason, int libraryLinks, int files) {
        List<GroupVerdict> verdicts = Decide.decide(snapshot(members), RULES, NOW);

        assertEquals(1, verdicts.size());
        GroupVerdict group = verdicts.get(0);
        assertEquals(List.of("a", "b"), group.members().stream().map(Member::hash).toList());
        assertEquals(verdict, group.verdict());
        assertEquals(reason, group.reason());
        assertEquals(libraryLinks, group.libraryLinks());
        assertEquals(files, group.files());
    }

    private static final Optional<Forecast> CLOCK_STOPPED = Optional.of(new Forecast(Optional.empty()));
    private static final Optional<Forecast> NO_FORECAST = Optional.empty();

    private static Optional<Forecast> on(String date) {
        return Optional.of(new Forecast(Optional.of(ZonedDateTime.parse(date))));
    }

    static Stream<Arguments> forecasts() {
        return Stream.of(
                argumentSet("one member, 10 days to go",
                        List.of(torrent().seeded(20 * DAY)), NOW,
                        on("2026-11-25T10:00:00+01:00[Europe/Brussels]")),
                argumentSet("one second to go",
                        List.of(torrent().seeded(30 * DAY - 1)), NOW,
                        on("2026-11-15T10:00:01+01:00[Europe/Brussels]")),
                argumentSet("the member of the tracker with the longer minimum is the latest",
                        List.of(a().seeded(20 * DAY), b().seeded(13 * DAY)), NOW,
                        on("2026-11-25T10:00:00+01:00[Europe/Brussels]")),
                argumentSet("the member of the tracker with the shorter minimum is the latest",
                        List.of(a().seeded(29 * DAY), b().seeded(2 * DAY)), NOW,
                        on("2026-11-27T10:00:00+01:00[Europe/Brussels]")),
                argumentSet("a member that has met its minimum does not count",
                        List.of(a().seeded(400 * DAY), b().seeded(13 * DAY + DAY / 2)), NOW,
                        on("2026-11-15T22:00:00+01:00[Europe/Brussels]")),
                argumentSet("the latest member is paused: clock stopped",
                        List.of(a().seeded(20 * DAY).paused(), b().seeded(13 * DAY)), NOW,
                        CLOCK_STOPPED),
                argumentSet("the only member is paused: clock stopped",
                        List.of(torrent().seeded(20 * DAY).paused()), NOW,
                        CLOCK_STOPPED),
                argumentSet("a paused member that is not the latest does not stop the clock",
                        List.of(a().seeded(20 * DAY), b().seeded(13 * DAY).paused()), NOW,
                        on("2026-11-25T10:00:00+01:00[Europe/Brussels]")),
                argumentSet("a paused member that has met its minimum does not stop the clock",
                        List.of(a().seeded(20 * DAY), b().seeded(14 * DAY).paused()), NOW,
                        on("2026-11-25T10:00:00+01:00[Europe/Brussels]")),
                argumentSet("two members are the latest and one of them is paused: clock stopped",
                        List.of(a().seeded(20 * DAY), b().seeded(4 * DAY).paused()), NOW,
                        CLOCK_STOPPED),
                argumentSet("the date is in the time zone of now, across the change to summer time",
                        List.of(torrent().seeded(20 * DAY)),
                        ZonedDateTime.parse("2027-03-20T10:00:00+01:00[Europe/Brussels]"),
                        on("2027-03-30T11:00:00+02:00[Europe/Brussels]")),
                argumentSet("the date is in the time zone of now, another zone",
                        List.of(torrent().seeded(20 * DAY)),
                        ZonedDateTime.parse("2026-11-15T04:00:00-05:00[America/New_York]"),
                        on("2026-11-25T04:00:00-05:00[America/New_York]")),
                argumentSet("library link: no forecast, though the minimum is not reached either",
                        List.of(torrent().seeded(20 * DAY).wanted(IN_LIBRARY)), NOW, NO_FORECAST),
                argumentSet("not finished: no forecast",
                        List.of(torrent().percentDone(0.5, Status.DOWNLOADING)), NOW, NO_FORECAST),
                argumentSet("unmanaged: no forecast",
                        List.of(torrent().announce(UNKNOWN)), NOW, NO_FORECAST),
                argumentSet("all met: no forecast",
                        List.of(torrent().seeded(30 * DAY)), NOW, NO_FORECAST));
    }

    @ParameterizedTest
    @MethodSource("forecasts")
    void minimumNotReachedCarriesAForecast(List<Case> members, ZonedDateTime now, Optional<Forecast> forecast) {
        List<GroupVerdict> verdicts = Decide.decide(snapshot(members), RULES, now);

        assertEquals(1, verdicts.size());
        assertEquals(forecast, verdicts.get(0).forecast());
    }

    @Test
    void aMemberCarriesItsTrackerSeedTimeAndMinimum() {
        List<GroupVerdict> verdicts = Decide.decide(torrent().announce(TRACKER_B).seeded(3 * DAY).snapshot(), RULES, NOW);

        assertEquals(List.of(new Member("hash-1", "Torrent 1", Optional.of(new Tracker("tracker-b",
                Duration.ofDays(14))), Duration.ofDays(3), Status.SEEDING)), verdicts.get(0).members());
    }

    @Test
    void anUnmanagedMemberHasNoTracker() {
        List<GroupVerdict> verdicts = Decide.decide(torrent().announce(UNKNOWN).snapshot(), RULES, NOW);

        assertEquals(Optional.empty(), verdicts.get(0).members().get(0).tracker());
    }

    @Test
    void torrentsThatShareNoFileAreEachAGroupInSnapshotOrder() {
        Snapshot both = snapshot(List.of(torrent().seeded(30 * DAY), torrent().hash("hash-2").wanted(IN_LIBRARY)));

        List<GroupVerdict> verdicts = Decide.decide(both, RULES, NOW);

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
