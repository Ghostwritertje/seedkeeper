package io.github.ghostwritertje.seedkeeper.decide;

import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The verdict on one group, with the reason that decided it.
 *
 * @param libraryLinks how many wanted files of the members have a library link
 * @param files        how many wanted files the members have
 */
public record GroupVerdict(List<Member> members, Verdict verdict, Reason reason, int libraryLinks, int files) {

    public GroupVerdict {
        members = List.copyOf(members);
    }

    /**
     * @param tracker  the tracker the torrent belongs to; empty when it is unmanaged
     * @param seedTime time actually spent seeding
     */
    public record Member(String hash, String name, Optional<Tracker> tracker, Duration seedTime,
            Torrent.Status status) {
    }
}
