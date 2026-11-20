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
 * @param forecast     present when the reason is <em>minimum not reached</em>, or <em>keep override</em> with an
 *                     end date
 * @param secondaryReasons every other reason that holds too, in the order of precedence
 * @param missingFiles how many wanted files of finished members resolve to nothing on disk
 * @param crossSeedGroup the info hashes of every torrent of the group's cross-seed group, in snapshot order: the
 *                     members, and also an unregistered torrent that split off from it
 * @param size         the bytes of the group's wanted files, a file that several members share counted once, the way
 *                     {@code files} counts it: the size of its data, not the space a removal would free
 */
public record GroupVerdict(
        List<Member> members,
        Verdict verdict,
        Reason reason,
        int libraryLinks,
        int files,
        Optional<Forecast> forecast,
        List<Reason> secondaryReasons,
        int missingFiles,
        List<String> crossSeedGroup,
        long size) {

    public GroupVerdict {
        members = List.copyOf(members);
        secondaryReasons = List.copyOf(secondaryReasons);
        crossSeedGroup = List.copyOf(crossSeedGroup);
    }

    /** A verdict on a group that is its whole cross-seed group, with a size of 0. */
    public GroupVerdict(
            List<Member> members,
            Verdict verdict,
            Reason reason,
            int libraryLinks,
            int files,
            Optional<Forecast> forecast,
            List<Reason> secondaryReasons,
            int missingFiles) {
        this(
                members,
                verdict,
                reason,
                libraryLinks,
                files,
                forecast,
                secondaryReasons,
                missingFiles,
                members.stream().map(Member::hash).toList(),
                0);
    }

    /** A verdict with no secondary reason, no file missing and a size of 0. */
    public GroupVerdict(
            List<Member> members,
            Verdict verdict,
            Reason reason,
            int libraryLinks,
            int files,
            Optional<Forecast> forecast) {
        this(members, verdict, reason, libraryLinks, files, forecast, List.of(), 0);
    }

    /**
     * One torrent of the group, with what the verdict took into account.
     *
     * @param tracker  the tracker the torrent belongs to; empty when it is unmanaged
     * @param seedTime time actually spent seeding
     * @param trackerError what the tracker reports to be wrong with the torrent; empty when nothing
     * @param announceHosts the hosts of the torrent's announce URLs, once each, in announce order; never the full
     *                      URL, which may hold a passkey
     * @param size     the bytes of the torrent's wanted files
     * @param causes   the reasons that hold for the group because of this torrent, in the order of precedence: of
     *                 {@link Reason#UNMANAGED}, {@link Reason#MISSING_DATA}, {@link Reason#LOCAL_ERROR} and
     *                 {@link Reason#VERIFYING}, the ones it makes hold
     */
    public record Member(
            String hash,
            String name,
            Optional<Tracker> tracker,
            Duration seedTime,
            Torrent.Status status,
            Optional<TrackerError> trackerError,
            List<String> announceHosts,
            long size,
            List<Reason> causes) {

        public Member {
            announceHosts = List.copyOf(announceHosts);
            causes = List.copyOf(causes);
        }

        /** A member that causes no reason. */
        public Member(
                String hash,
                String name,
                Optional<Tracker> tracker,
                Duration seedTime,
                Torrent.Status status,
                Optional<TrackerError> trackerError,
                List<String> announceHosts,
                long size) {
            this(hash, name, tracker, seedTime, status, trackerError, announceHosts, size, List.of());
        }

        /** A member with no announce hosts and a size of 0. */
        public Member(
                String hash,
                String name,
                Optional<Tracker> tracker,
                Duration seedTime,
                Torrent.Status status,
                Optional<TrackerError> trackerError) {
            this(hash, name, tracker, seedTime, status, trackerError, List.of(), 0);
        }

        /** A member whose tracker reports nothing wrong, with no announce hosts and a size of 0. */
        public Member(String hash, String name, Optional<Tracker> tracker, Duration seedTime, Torrent.Status status) {
            this(hash, name, tracker, seedTime, status, Optional.empty());
        }

        /** How much seed time is still to go to the tracker's minimum; zero when it is met or there is no tracker. */
        Duration remaining() {
            Duration remaining =
                    tracker.map(t -> t.minimumSeedTime().minus(seedTime)).orElse(Duration.ZERO);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }
    }
}
