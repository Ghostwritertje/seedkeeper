package io.github.ghostwritertje.seedkeeper.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ghostwritertje.seedkeeper.decide.Forecast;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.decide.Tracker;
import io.github.ghostwritertje.seedkeeper.decide.TrackerError;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** How the Groups view writes a group: its header row, its member rows, and where a long name may break. */
class GroupsPageTest {

    private static final Tracker TRACKER_A = new Tracker("tracker-a", Duration.ofDays(30));

    @Test
    void aNameMayBreakAfterEachDotAndDash() {
        assertEquals(
                List.of("ubuntu-", "24.", "04.", "1-", "desktop-", "amd64.", "iso"),
                GroupsPage.breakable("ubuntu-24.04.1-desktop-amd64.iso"));
        assertEquals(List.of("Some Movie (2020)"), GroupsPage.breakable("Some Movie (2020)"));
        // Nothing follows the last dot: no break after it.
        assertEquals(List.of("Ends.", "with."), GroupsPage.breakable("Ends.with."));
    }

    @Test
    void aGroupOfOneIsItsHeaderRowAlone() {
        GroupVerdict verdict = new GroupVerdict(
                List.of(member(
                        "1111",
                        "Gone.Movie",
                        Optional.of(new TrackerError(TrackerError.Kind.UNREGISTERED, "Unregistered torrent")))),
                Verdict.REMOVE,
                Reason.UNREGISTERED,
                1,
                2,
                Optional.empty(),
                List.of(Reason.LIBRARY_LINK, Reason.MINIMUM_NOT_REACHED),
                0);

        GroupsPage.Group group = GroupsPage.group(verdict);

        assertEquals(List.of("Gone.", "Movie"), group.name());
        assertEquals("Gone.Movie", group.label());
        assertEquals("tracker-a · Seeding · 1 of 2 files linked", group.summary());
        assertEquals(new GroupsPage.SeedProgress("warn", 3, "3%", "1d of 30d · 29d to go"), group.progress());
        assertEquals("Unregistered: Unregistered torrent", group.trackerMessage());
        assertEquals("Unregistered", group.reason());
        // The library links are in the summary; the reason doesn't count them again.
        assertEquals("Library link, Minimum not reached", group.secondaryReasons());
        assertEquals(List.of(), group.members());
        assertEquals(1, group.rows());
        assertEquals(List.of("1111"), group.hashes());
    }

    @Test
    void aGroupOfMoreHasAMemberRowForEach() {
        GroupVerdict verdict = new GroupVerdict(
                List.of(
                        member("1111", "Cross.Seed", Optional.empty()),
                        member("2222", "Cross-Seed.Elsewhere", Optional.empty())),
                Verdict.KEEP,
                Reason.KEEP_OVERRIDE,
                0,
                1,
                Optional.of(new Forecast(Optional.empty(), Optional.of(LocalDate.of(2027, 3, 1)))));

        GroupsPage.Group group = GroupsPage.group(verdict);

        assertEquals(List.of("Cross.", "Seed"), group.name());
        assertEquals("2 torrents · 0 of 1 files linked", group.summary());
        assertEquals("", group.trackerMessage());
        assertEquals("until 2027-03-01", group.forecast());
        assertEquals(3, group.rows());
        GroupsPage.SeedProgress progress = new GroupsPage.SeedProgress("warn", 3, "3%", "1d of 30d · 29d to go");
        assertEquals(
                List.of(
                        new GroupsPage.Member(List.of("Cross.", "Seed"), "tracker-a", progress, "Seeding", ""),
                        new GroupsPage.Member(
                                List.of("Cross-", "Seed.", "Elsewhere"), "tracker-a", progress, "Seeding", "")),
                group.members());
    }

    @Test
    void missingDataKeepsItsCountInTheReason() {
        GroupVerdict verdict = new GroupVerdict(
                List.of(member("1111", "Half.Gone", Optional.empty())),
                Verdict.LEFT_ALONE,
                Reason.MISSING_DATA,
                0,
                2,
                Optional.empty(),
                List.of(),
                1);

        assertEquals("Missing data (1 of 2 files)", GroupsPage.group(verdict).reason());
    }

    @Test
    void seedProgressBelowTheMinimumIsARoundedPercentWithTheTimeToGo() {
        assertEquals(
                new GroupsPage.SeedProgress("warn", 0, "0%", "0m of 30d · 30d to go"),
                progress(Optional.of(TRACKER_A), Duration.ZERO));
        assertEquals(
                new GroupsPage.SeedProgress("warn", 33, "33%", "9d 20h of 30d · 20d 4h to go"),
                progress(Optional.of(TRACKER_A), Duration.ofDays(9).plusHours(20)));
        // Just under rounds to 100%, which would read as met: it stays at 99%.
        assertEquals(
                new GroupsPage.SeedProgress("warn", 99, "99%", "29d 23h of 30d · 1m to go"),
                progress(Optional.of(TRACKER_A), Duration.ofDays(30).minusMinutes(1)));
    }

    @Test
    void seedProgressAtOrOverTheMinimumIsMetWithTheTimeSince() {
        assertEquals(
                new GroupsPage.SeedProgress("ok", 100, "Met ✓", "30d of 30d · met just now"),
                progress(Optional.of(TRACKER_A), Duration.ofDays(30)));
        assertEquals(
                new GroupsPage.SeedProgress("ok", 100, "Met ✓", "168d 10h of 30d · met 138d 10h ago"),
                progress(Optional.of(TRACKER_A), Duration.ofDays(168).plusHours(10)));
    }

    @Test
    void seedProgressWithNoMinimumIsADash() {
        assertEquals(
                new GroupsPage.SeedProgress("muted", 0, "—", "400d · no minimum"),
                progress(Optional.empty(), Duration.ofDays(400)));
    }

    @Test
    void theHeaderRowOfAGroupShowsItsSlowestManagedMember() {
        Tracker trackerB = new Tracker("tracker-b", Duration.ofDays(14));
        GroupVerdict verdict = new GroupVerdict(
                List.of(
                        // 20 of 30 days, 10 to go: further along, but the slowest to meet its minimum.
                        new GroupVerdict.Member(
                                "1111",
                                "Cross.Seed",
                                Optional.of(TRACKER_A),
                                Duration.ofDays(20),
                                Torrent.Status.SEEDING),
                        new GroupVerdict.Member(
                                "2222",
                                "Cross.Seed.Elsewhere",
                                Optional.of(trackerB),
                                Duration.ofDays(7),
                                Torrent.Status.SEEDING),
                        new GroupVerdict.Member(
                                "3333",
                                "Cross.Seed.Unmanaged",
                                Optional.empty(),
                                Duration.ofDays(1),
                                Torrent.Status.SEEDING)),
                Verdict.LEFT_ALONE,
                Reason.UNMANAGED,
                0,
                1,
                Optional.empty());

        GroupsPage.Group group = GroupsPage.group(verdict);

        assertEquals(
                new GroupsPage.SeedProgress("warn", 67, "67%", "slowest: 20d of 30d · 10d to go"), group.progress());
        assertEquals(
                List.of("67%", "50%", "—"),
                group.members().stream().map(m -> m.progress().text()).toList());
    }

    @Test
    void theHeaderRowOfAGroupThatHasMetIsTheLastMemberToMeetIt() {
        GroupVerdict verdict = new GroupVerdict(
                List.of(
                        new GroupVerdict.Member(
                                "1111", "Old", Optional.of(TRACKER_A), Duration.ofDays(90), Torrent.Status.SEEDING),
                        new GroupVerdict.Member(
                                "2222", "Newer", Optional.of(TRACKER_A), Duration.ofDays(31), Torrent.Status.SEEDING)),
                Verdict.REMOVE,
                Reason.ALL_MET,
                0,
                1,
                Optional.empty());

        assertEquals(
                new GroupsPage.SeedProgress("ok", 100, "Met ✓", "slowest: 31d of 30d · met 1d ago"),
                GroupsPage.group(verdict).progress());
    }

    @Test
    void theHeaderRowOfAGroupWithNoManagedMemberIsADash() {
        GroupVerdict verdict = new GroupVerdict(
                List.of(
                        new GroupVerdict.Member(
                                "1111", "One", Optional.empty(), Duration.ofDays(2), Torrent.Status.SEEDING),
                        new GroupVerdict.Member(
                                "2222", "Two", Optional.empty(), Duration.ofDays(3), Torrent.Status.SEEDING)),
                Verdict.LEFT_ALONE,
                Reason.UNMANAGED,
                0,
                1,
                Optional.empty());

        assertEquals(
                new GroupsPage.SeedProgress("muted", 0, "—", "no member has a minimum"),
                GroupsPage.group(verdict).progress());
    }

    private static GroupsPage.SeedProgress progress(Optional<Tracker> tracker, Duration seedTime) {
        return GroupsPage.group(new GroupVerdict(
                        List.of(new GroupVerdict.Member("1111", "One", tracker, seedTime, Torrent.Status.SEEDING)),
                        Verdict.KEEP,
                        Reason.MINIMUM_NOT_REACHED,
                        0,
                        1,
                        Optional.empty()))
                .progress();
    }

    private static GroupVerdict.Member member(String hash, String name, Optional<TrackerError> error) {
        return new GroupVerdict.Member(
                hash, name, Optional.of(TRACKER_A), Duration.ofDays(1), Torrent.Status.SEEDING, error);
    }
}
