package io.github.ghostwritertje.seedkeeper.decide;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ghostwritertje.seedkeeper.decide.TrackerRules.ManagedTracker;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tracker attribution (spec §3.2). */
class TrackerRulesTest {

    private static final Duration DEFAULT = Duration.ofDays(30);
    private static final List<ManagedTracker> MANAGED = List.of(
            new ManagedTracker("tracker-a", List.of("tracker-a.example"), Optional.empty()),
            new ManagedTracker("tracker-b", List.of("tracker-b.example", "tracker-b.example.org"),
                    Optional.of(Duration.ofDays(14))));

    private final TrackerRules withoutPublic = new TrackerRules(MANAGED, DEFAULT, Optional.empty());
    private final TrackerRules withPublic = new TrackerRules(MANAGED, DEFAULT, Optional.of(Duration.ofDays(1)));

    @Test
    void allHostsOfOneManagedTrackerGiveThatTrackerWithTheDefaultMinimum() {
        assertEquals(Optional.of(new Tracker("tracker-a", Duration.ofDays(30))),
                withoutPublic.attribute(List.of("https://tracker-a.example/abc123/announce"), true));
    }

    @Test
    void aTrackersOwnMinimumReplacesTheDefault() {
        assertEquals(Optional.of(new Tracker("tracker-b", Duration.ofDays(14))),
                withoutPublic.attribute(List.of("https://tracker-b.example/announce",
                        "udp://TRACKER-B.example.org:6969/announce"), true));
    }

    @Test
    void hostsOfTwoTrackersAreUnmanaged() {
        assertEquals(Optional.empty(), withoutPublic.attribute(
                List.of("https://tracker-a.example/announce", "https://tracker-b.example/announce"), true));
    }

    @Test
    void aHostOfNoTrackerIsUnmanaged() {
        assertEquals(Optional.empty(),
                withoutPublic.attribute(List.of("https://elsewhere.example/announce"), true));
    }

    @Test
    void aManagedHostNextToAnUnknownHostIsUnmanaged() {
        assertEquals(Optional.empty(), withoutPublic.attribute(
                List.of("https://tracker-a.example/announce", "https://elsewhere.example/announce"), true));
    }

    @Test
    void aSubdomainOfAManagedHostIsNotThatHost() {
        assertEquals(Optional.empty(),
                withoutPublic.attribute(List.of("https://evil.tracker-a.example/announce"), true));
    }

    @Test
    void noTrackersAtAllIsUnmanaged() {
        assertEquals(Optional.empty(), withoutPublic.attribute(List.of(), true));
    }

    @Test
    void nonPrivateWithoutAPublicEntryIsUnmanaged() {
        assertEquals(Optional.empty(),
                withoutPublic.attribute(List.of("udp://open.example:1337/announce"), false));
    }

    @Test
    void nonPrivateWithAPublicEntryIsThePublicTracker() {
        assertEquals(Optional.of(new Tracker("public", Duration.ofDays(1))),
                withPublic.attribute(List.of("udp://open.example:1337/announce"), false));
    }

    @Test
    void privateWithUnknownHostsStaysUnmanagedWithAPublicEntry() {
        assertEquals(Optional.empty(),
                withPublic.attribute(List.of("https://elsewhere.example/announce"), true));
    }

    @Test
    void aManagedTrackerWinsOverPublicForANonPrivateTorrent() {
        assertEquals(Optional.of(new Tracker("tracker-a", Duration.ofDays(30))),
                withPublic.attribute(List.of("https://tracker-a.example/announce"), false));
    }
}
