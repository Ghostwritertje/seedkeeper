package io.github.ghostwritertje.seedkeeper.decide;

import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The verdict on one group, with the reason that decided it. An unregistered torrent that leaves its group is a group
 * of its own here, with reason {@link Reason#UNREGISTERED}: the members of a {@link Verdict#REMOVE} verdict are
 * exactly the torrents to remove.
 *
 * @param libraryLinks how many wanted files of the group have a library link
 * @param files        how many wanted files the group has; a file that several members share counts once
 * @param forecast     present only when the reason is <em>minimum not reached</em>
 * @param secondaryReasons every other reason that holds too, in the order of precedence
 * @param missingFiles how many wanted files of finished members resolve to nothing on disk
 */
public record GroupVerdict(List<Member> members, Verdict verdict, Reason reason, int libraryLinks, int files,
        Optional<Forecast> forecast, List<Reason> secondaryReasons, int missingFiles) {

    public GroupVerdict {
        members = List.copyOf(members);
        secondaryReasons = List.copyOf(secondaryReasons);
    }

    /** A verdict with no secondary reason and no file missing. */
    public GroupVerdict(List<Member> members, Verdict verdict, Reason reason, int libraryLinks, int files,
            Optional<Forecast> forecast) {
        this(members, verdict, reason, libraryLinks, files, forecast, List.of(), 0);
    }

    /**
     * @param tracker  the tracker the torrent belongs to; empty when it is unmanaged
     * @param seedTime time actually spent seeding
     * @param trackerError what the tracker reports to be wrong with the torrent; empty when nothing
     */
    public record Member(String hash, String name, Optional<Tracker> tracker, Duration seedTime,
            Torrent.Status status, Optional<TrackerError> trackerError) {

        /** A member whose tracker reports nothing wrong. */
        public Member(String hash, String name, Optional<Tracker> tracker, Duration seedTime,
                Torrent.Status status) {
            this(hash, name, tracker, seedTime, status, Optional.empty());
        }

        /** How much seed time is still to go to the tracker's minimum; zero when it is met or there is no tracker. */
        Duration remaining() {
            Duration remaining = tracker.map(t -> t.minimumSeedTime().minus(seedTime)).orElse(Duration.ZERO);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }
    }
}
