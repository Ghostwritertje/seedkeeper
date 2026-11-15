package io.github.ghostwritertje.seedkeeper.decide;

import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The verdict on one group, with the reason that decided it.
 *
 * @param libraryLinks how many wanted files of the group have a library link
 * @param files        how many wanted files the group has; a file that several members share counts once
 * @param forecast     present only when the reason is <em>minimum not reached</em>
 */
public record GroupVerdict(List<Member> members, Verdict verdict, Reason reason, int libraryLinks, int files,
        Optional<Forecast> forecast) {

    public GroupVerdict {
        members = List.copyOf(members);
    }

    /**
     * @param tracker  the tracker the torrent belongs to; empty when it is unmanaged
     * @param seedTime time actually spent seeding
     */
    public record Member(String hash, String name, Optional<Tracker> tracker, Duration seedTime,
            Torrent.Status status) {

        /** How much seed time is still to go to the tracker's minimum; zero when it is met or there is no tracker. */
        Duration remaining() {
            Duration remaining = tracker.map(t -> t.minimumSeedTime().minus(seedTime)).orElse(Duration.ZERO);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }
    }
}
