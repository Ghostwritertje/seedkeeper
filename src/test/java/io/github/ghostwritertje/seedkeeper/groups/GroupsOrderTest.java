package io.github.ghostwritertje.seedkeeper.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ghostwritertje.seedkeeper.decide.Forecast;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.decide.Tracker;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The order of the Groups view: by the column chosen, either way, and the default order when none is. */
class GroupsOrderTest {

    private static final Tracker TRACKER_A = new Tracker("tracker-a", Duration.ofDays(10));
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneOffset.UTC);

    private static final GroupVerdict KEPT_LONG =
            group("Kept.Long", Verdict.KEEP, Optional.of(dated(NOW.plusDays(9))), Optional.of(TRACKER_A), 1, 300);
    private static final GroupVerdict LINKED =
            group("linked.show", Verdict.KEEP, Optional.empty(), Optional.of(TRACKER_A), 20, 100);
    private static final GroupVerdict REMOVED_B =
            group("Removed.B", Verdict.REMOVE, Optional.empty(), Optional.of(TRACKER_A), 30, 50);
    private static final GroupVerdict UNMANAGED =
            group("Unmanaged", Verdict.LEFT_ALONE, Optional.empty(), Optional.empty(), 400, 400);
    private static final GroupVerdict PAUSED = group(
            "Paused",
            Verdict.KEEP,
            Optional.of(new Forecast(Optional.empty(), Optional.empty())),
            Optional.of(TRACKER_A),
            9,
            200);
    private static final GroupVerdict KEPT_SOON =
            group("Kept.Soon", Verdict.KEEP, Optional.of(dated(NOW.plusDays(2))), Optional.of(TRACKER_A), 8, 10);
    private static final GroupVerdict OVERRIDDEN = group(
            "Overridden",
            Verdict.KEEP,
            // Ends with the day Kept.Soon's minimum is met on, at noon: after it.
            Optional.of(new Forecast(Optional.empty(), Optional.of(LocalDate.of(2026, 11, 22)))),
            Optional.of(TRACKER_A),
            5,
            20);
    private static final GroupVerdict REMOVED_A =
            group("Removed.A", Verdict.REMOVE, Optional.empty(), Optional.of(TRACKER_A), 12, 60);

    /** In snapshot order. */
    private static final List<GroupVerdict> GROUPS =
            List.of(KEPT_LONG, LINKED, REMOVED_B, UNMANAGED, PAUSED, KEPT_SOON, OVERRIDDEN, REMOVED_A);

    @Test
    void byDefaultEachLaneHasItsOwnOrderRemoveLargestFirstKeepBySoonestForecast() {
        // Remove, largest first. Keep: a keep override's end date is inclusive: it ends as that day does. A stopped
        // clock has no date, so it comes after every date; a group with no forecast comes last, in snapshot order.
        assertEquals(
                List.of(
                        "Removed.A",
                        "Removed.B",
                        "Unmanaged",
                        "Kept.Soon",
                        "Overridden",
                        "Kept.Long",
                        "Paused",
                        "linked.show"),
                names(GroupsOrder.DEFAULT));
        assertEquals(GroupsOrder.DEFAULT, GroupsOrder.parse(null, null));
        assertEquals(GroupsOrder.DEFAULT, GroupsOrder.parse("verdict", "asc"));
    }

    @Test
    void theVerdictTheOtherWayRoundIsTheDefaultOrderReversed() {
        assertEquals(
                List.of(
                        "linked.show",
                        "Paused",
                        "Kept.Long",
                        "Overridden",
                        "Kept.Soon",
                        "Unmanaged",
                        "Removed.B",
                        "Removed.A"),
                names(GroupsOrder.parse("verdict", "desc")));
    }

    @Test
    void byDefaultTheGroupsLeftAloneAreByReasonThenSoonestForecast() {
        GroupVerdict broken = leftAlone("Broken", Reason.LOCAL_ERROR);
        GroupVerdict missing = leftAlone("Missing", Reason.MISSING_DATA);
        GroupVerdict unmanaged = leftAlone("Unmanaged", Reason.UNMANAGED);

        // In the order of precedence of the reasons.
        assertEquals(
                List.of("Unmanaged", "Missing", "Broken"),
                GroupsOrder.DEFAULT.sort(List.of(broken, missing, unmanaged)).stream()
                        .map(group -> group.members().getFirst().name())
                        .toList());
    }

    private static GroupVerdict leftAlone(String name, Reason reason) {
        return new GroupVerdict(
                List.of(new GroupVerdict.Member(name, name, Optional.empty(), Duration.ZERO, Torrent.Status.SEEDING)),
                Verdict.LEFT_ALONE,
                reason,
                0,
                1,
                Optional.empty());
    }

    @Test
    void byNameIgnoringCase() {
        assertEquals(
                List.of(
                        "Kept.Long",
                        "Kept.Soon",
                        "linked.show",
                        "Overridden",
                        "Paused",
                        "Removed.A",
                        "Removed.B",
                        "Unmanaged"),
                names(GroupsOrder.parse("name", null)));
        assertEquals(
                List.of(
                        "Unmanaged",
                        "Removed.B",
                        "Removed.A",
                        "Paused",
                        "Overridden",
                        "linked.show",
                        "Kept.Soon",
                        "Kept.Long"),
                names(GroupsOrder.parse("name", "desc")));
    }

    @Test
    void bySize() {
        assertEquals(
                List.of(
                        "Kept.Soon",
                        "Overridden",
                        "Removed.B",
                        "Removed.A",
                        "linked.show",
                        "Paused",
                        "Kept.Long",
                        "Unmanaged"),
                names(GroupsOrder.parse("size", "asc")));
        assertEquals(
                List.of(
                        "Unmanaged",
                        "Kept.Long",
                        "Paused",
                        "linked.show",
                        "Removed.A",
                        "Removed.B",
                        "Overridden",
                        "Kept.Soon"),
                names(GroupsOrder.parse("size", "desc")));
    }

    @Test
    void bySeedTimeTowardTheMinimumWithNoMinimumLastEitherWay() {
        assertEquals(
                List.of(
                        "Kept.Long",
                        "Overridden",
                        "Kept.Soon",
                        "Paused",
                        "Removed.A",
                        "linked.show",
                        "Removed.B",
                        "Unmanaged"),
                names(GroupsOrder.parse("seeding", "asc")));
        assertEquals(
                List.of(
                        "Removed.B",
                        "linked.show",
                        "Removed.A",
                        "Paused",
                        "Kept.Soon",
                        "Overridden",
                        "Kept.Long",
                        "Unmanaged"),
                names(GroupsOrder.parse("seeding", "desc")));
    }

    @Test
    void byForecastWithAStoppedClockThenNoForecastLastEitherWay() {
        // The groups with no forecast keep the default order among themselves: Remove largest first.
        assertEquals(
                List.of(
                        "Kept.Soon",
                        "Overridden",
                        "Kept.Long",
                        "Paused",
                        "Removed.A",
                        "Removed.B",
                        "Unmanaged",
                        "linked.show"),
                names(GroupsOrder.parse("forecast", "asc")));
        assertEquals(
                List.of(
                        "Kept.Long",
                        "Overridden",
                        "Kept.Soon",
                        "Paused",
                        "Removed.A",
                        "Removed.B",
                        "Unmanaged",
                        "linked.show"),
                names(GroupsOrder.parse("forecast", "desc")));
    }

    @Test
    void anUnknownSortOrDirectionIsTheDefault() {
        assertEquals(GroupsOrder.DEFAULT, GroupsOrder.parse("tracker", "desc"));
        assertEquals(GroupsOrder.parse("name", "asc"), GroupsOrder.parse(" name ", "sideways"));
        assertEquals(GroupsOrder.parse("name", "desc"), GroupsOrder.parse("name", " desc "));
    }

    private static List<String> names(GroupsOrder order) {
        return order.sort(GROUPS).stream()
                .map(group -> group.members().getFirst().name())
                .toList();
    }

    private static Forecast dated(ZonedDateTime date) {
        return new Forecast(Optional.of(date), Optional.empty());
    }

    private static GroupVerdict group(
            String name,
            Verdict verdict,
            Optional<Forecast> forecast,
            Optional<Tracker> tracker,
            int seedDays,
            long size) {
        GroupVerdict.Member member = new GroupVerdict.Member(
                name,
                name,
                tracker,
                Duration.ofDays(seedDays),
                Torrent.Status.SEEDING,
                Optional.empty(),
                List.of(),
                size);
        return new GroupVerdict(
                List.of(member),
                verdict,
                verdict == Verdict.REMOVE ? Reason.ALL_MET : Reason.MINIMUM_NOT_REACHED,
                0,
                1,
                forecast,
                List.of(),
                0,
                List.of(name),
                size);
    }
}
