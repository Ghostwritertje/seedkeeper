package io.github.ghostwritertje.seedkeeper.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The name search of the Groups view: a group matches when the name of one of its members contains the text. */
class GroupsSearchTest {

    private static final GroupVerdict CROSS_SEED =
            group(Verdict.KEEP, Reason.MINIMUM_NOT_REACHED, "Some.Movie.2020.1080p", "Some.Movie.2020.REPACK");

    @Test
    void aGroupMatchesWhenTheNameOfAnyMemberContainsTheTextIgnoringCase() {
        assertTrue(search("movie.2020").matches(CROSS_SEED));
        assertTrue(search("  repack ").matches(CROSS_SEED));
        assertFalse(search("2160p").matches(CROSS_SEED));
    }

    @Test
    void noTextMatchesEveryGroupAndIsNoFilter() {
        assertTrue(search(null).matches(CROSS_SEED));
        assertTrue(search("  ").matches(CROSS_SEED));
        assertFalse(search(" ").active());
        assertTrue(search("x").active());
    }

    @Test
    void theNameWorksTogetherWithTheVerdictAndTheReason() {
        assertTrue(GroupsFilter.parse("keep", "minimum-not-reached", "REPACK").matches(CROSS_SEED));
        assertFalse(GroupsFilter.parse("remove", null, "repack").matches(CROSS_SEED));
        assertFalse(GroupsFilter.parse(null, "all-met", "repack").matches(CROSS_SEED));
        assertFalse(GroupsFilter.parse("keep", null, "other").matches(CROSS_SEED));
    }

    @Test
    void theCountsOfTheVerdictsAndOfTheReasonsEachLeaveOutTheirOwnSideAndKeepTheRest() {
        GroupsFilter filter = GroupsFilter.parse("remove", "all-met", "repack");

        assertEquals(new GroupsFilter(Optional.empty(), Optional.of(Reason.ALL_MET), "repack"), filter.anyVerdict());
        assertEquals(new GroupsFilter(Optional.of(Verdict.REMOVE), Optional.empty(), "repack"), filter.anyReason());
    }

    private static GroupsFilter search(@Nullable String name) {
        return GroupsFilter.parse(null, null, name);
    }

    private static GroupVerdict group(Verdict verdict, Reason reason, String... names) {
        return new GroupVerdict(
                List.of(names).stream()
                        .map(name -> new GroupVerdict.Member(
                                name, name, Optional.empty(), Duration.ZERO, Torrent.Status.SEEDING))
                        .toList(),
                verdict,
                reason,
                0,
                1,
                Optional.empty());
    }
}
