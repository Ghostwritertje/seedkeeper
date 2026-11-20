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
                List.of(member("1111", "Gone.Movie", Optional.of(new TrackerError(
                        TrackerError.Kind.UNREGISTERED, "Unregistered torrent")))),
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
        assertEquals("1d / 30d", group.seedTime());
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
        assertEquals("", group.seedTime());
        assertEquals("", group.trackerMessage());
        assertEquals("until 2027-03-01", group.forecast());
        assertEquals(3, group.rows());
        assertEquals(
                List.of(
                        new GroupsPage.Member(List.of("Cross.", "Seed"), "tracker-a", "1d / 30d", "Seeding", ""),
                        new GroupsPage.Member(
                                List.of("Cross-", "Seed.", "Elsewhere"), "tracker-a", "1d / 30d", "Seeding", "")),
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

    private static GroupVerdict.Member member(String hash, String name, Optional<TrackerError> error) {
        return new GroupVerdict.Member(
                hash, name, Optional.of(TRACKER_A), Duration.ofDays(1), Torrent.Status.SEEDING, error);
    }
}
