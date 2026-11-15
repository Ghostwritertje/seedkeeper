package io.github.ghostwritertje.seedkeeper.decide;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict.Member;
import io.github.ghostwritertje.seedkeeper.snapshot.FileId;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.SnapshotTorrent;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.WantedFile;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The rules (spec §3.5): a pure function from a snapshot to a verdict per group. Two calls on the same snapshot with
 * the same now give the same verdicts.
 *
 * <p>The rules are those of {@link Reason}, in its order; keep override doesn't exist yet. Each holds
 * for the group when it holds for any member, so a group is removed only when every member has met its own tracker's
 * minimum. The first that holds decides the verdict, the others that hold are its secondary reasons.
 */
public final class Decide {

    /** Transmission's error number for a local error, such as files it can't read or write. */
    private static final int LOCAL_ERROR = 3;

    private Decide() {
    }

    /**
     * One verdict per group, in the order of each group's first member in the snapshot.
     *
     * <p>An unregistered torrent that is not verifying leaves its group (rule 1): it gets a verdict of its own,
     * {@link Verdict#REMOVE} with {@link Reason#UNREGISTERED} and itself as the only member. The groups are built
     * from the other torrents, so the rest of its group is judged without it. To carry out the verdicts, remove every
     * member of every {@link Verdict#REMOVE} verdict, and nothing else.
     *
     * @param now the moment of the snapshot, in the configured time zone; the forecast counts from it
     */
    public static List<GroupVerdict> decide(Snapshot snapshot, TrackerRules trackers, ZonedDateTime now) {
        Map<SnapshotTorrent, Integer> position = new IdentityHashMap<>();
        snapshot.torrents().forEach(torrent -> position.put(torrent, position.size()));

        Map<Boolean, List<SnapshotTorrent>> byRule1 = snapshot.torrents().stream()
                .collect(Collectors.partitioningBy(member -> leavesItsGroup(member.torrent())));
        Snapshot rest = new Snapshot(byRule1.get(false), snapshot.library());

        Map<Integer, GroupVerdict> verdicts = new TreeMap<>();
        for (SnapshotTorrent alone : byRule1.get(true)) {
            verdicts.put(position.get(alone), decide(List.of(alone), true, snapshot, trackers, now));
        }
        for (List<SnapshotTorrent> group : rest.groups()) {
            verdicts.put(position.get(group.getFirst()), decide(group, false, snapshot, trackers, now));
        }
        return List.copyOf(verdicts.values());
    }

    /** Rule 1: unregistered and not verifying. */
    private static boolean leavesItsGroup(Torrent torrent) {
        return !verifying(torrent) && TrackerError.of(torrent.error(), torrent.errorString())
                .filter(TrackerError::unregistered).isPresent();
    }

    /**
     * @param unregistered true when the group is one unregistered torrent that left its group
     */
    private static GroupVerdict decide(List<SnapshotTorrent> group, boolean unregistered, Snapshot snapshot,
            TrackerRules trackers, ZonedDateTime now) {
        List<Member> members = group.stream().map(member -> member(member.torrent(), trackers)).toList();

        // A file that several members share is one file; a file that resolves to nothing is counted per member.
        Set<FileId> seen = new HashSet<>();
        List<WantedFile> files = group.stream()
                .flatMap(member -> member.wantedFiles().stream())
                .filter(file -> file.id().map(seen::add).orElse(true))
                .toList();
        int libraryLinks = (int) files.stream().filter(snapshot::hasLibraryLink).count();

        // An unfinished member is never missing data: its files may not exist yet.
        int missingFiles = (int) group.stream()
                .filter(member -> member.torrent().finished())
                .flatMap(member -> member.wantedFiles().stream())
                .filter(file -> file.id().isEmpty())
                .count();

        // Every reason that holds, in the order of precedence. Paused and tracker errors (1, and 2 that is not
        // unregistered) are not inputs.
        Set<Reason> holding = EnumSet.noneOf(Reason.class);
        if (unregistered) {
            holding.add(Reason.UNREGISTERED);
        }
        if (members.stream().anyMatch(member -> member.tracker().isEmpty())) {
            holding.add(Reason.UNMANAGED);
        }
        if (missingFiles > 0) {
            holding.add(Reason.MISSING_DATA);
        }
        if (group.stream().anyMatch(member -> member.torrent().error() == LOCAL_ERROR)) {
            holding.add(Reason.LOCAL_ERROR);
        }
        if (group.stream().anyMatch(member -> verifying(member.torrent()))) {
            holding.add(Reason.VERIFYING);
        }
        if (group.stream().anyMatch(member -> !member.torrent().finished())) {
            holding.add(Reason.NOT_FINISHED);
        }
        if (libraryLinks > 0) {
            holding.add(Reason.LIBRARY_LINK);
        }
        if (members.stream().anyMatch(member -> !member.remaining().isZero())) {
            holding.add(Reason.MINIMUM_NOT_REACHED);
        }
        Reason reason = holding.stream().findFirst().orElse(Reason.ALL_MET);
        Optional<Forecast> forecast = reason == Reason.MINIMUM_NOT_REACHED
                ? Optional.of(forecast(members, now))
                : Optional.empty();
        return new GroupVerdict(members, reason.verdict(), reason, libraryLinks, files.size(), forecast,
                holding.stream().skip(1).toList(), missingFiles);
    }

    private static boolean verifying(Torrent torrent) {
        return torrent.status() == Torrent.Status.CHECK_WAIT || torrent.status() == Torrent.Status.CHECKING;
    }

    private static Member member(Torrent torrent, TrackerRules trackers) {
        return new Member(torrent.hash(), torrent.name(), trackers.attribute(torrent.trackers(), torrent.isPrivate()),
                Duration.ofSeconds(torrent.secondsSeeding()), torrent.status(),
                TrackerError.of(torrent.error(), torrent.errorString()));
    }

    /**
     * Now plus the largest remaining seed time over the members (spec §3.6). No date when a member that sets it is
     * paused: its seed time stands still.
     */
    private static Forecast forecast(List<Member> members, ZonedDateTime now) {
        Duration largest = members.stream().map(Member::remaining).max(Duration::compareTo).orElse(Duration.ZERO);
        boolean setterPaused = members.stream()
                .anyMatch(member -> member.remaining().equals(largest) && member.status() == Torrent.Status.STOPPED);
        return new Forecast(setterPaused ? Optional.empty() : Optional.of(now.plus(largest)));
    }
}
