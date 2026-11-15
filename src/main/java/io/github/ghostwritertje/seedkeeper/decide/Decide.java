package io.github.ghostwritertje.seedkeeper.decide;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict.Member;
import io.github.ghostwritertje.seedkeeper.snapshot.FileId;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.SnapshotTorrent;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.WantedFile;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.github.ghostwritertje.seedkeeper.overrides.KeepOverride;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rules (spec §3.5): a pure function from a snapshot to a verdict per group. Two calls on the same snapshot with
 * the same now give the same verdicts.
 *
 * <p>The rules are those of {@link Reason}, in its order; unregistered doesn't exist yet. Each holds
 * for the group when it holds for any member, so a group is removed only when every member has met its own tracker's
 * minimum. The first that holds decides the verdict, the others that hold are its secondary reasons.
 */
public final class Decide {

    /** Transmission's error number for a local error, such as files it can't read or write. */
    private static final int LOCAL_ERROR = 3;

    private Decide() {
    }

    /** The verdicts when there are no keep overrides. */
    public static List<GroupVerdict> decide(Snapshot snapshot, TrackerRules trackers, ZonedDateTime now) {
        return decide(snapshot, trackers, List.of(), now);
    }

    /**
     * @param overrides the keep overrides, as read at the start of the run; expired and stale ones have no effect
     * @param now       the moment of the snapshot, in the configured time zone; the forecast counts from it and the
     *                  end date of an override is a date in its zone
     */
    public static List<GroupVerdict> decide(Snapshot snapshot, TrackerRules trackers, List<KeepOverride> overrides,
            ZonedDateTime now) {
        return snapshot.groups().stream().map(group -> decide(group, snapshot, trackers, overrides, now)).toList();
    }

    private static GroupVerdict decide(List<SnapshotTorrent> group, Snapshot snapshot, TrackerRules trackers,
            List<KeepOverride> overrides, ZonedDateTime now) {
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

        // Every reason that holds, in the order of precedence. Tracker errors (1 and 2) and paused are not inputs.
        Set<Reason> holding = EnumSet.noneOf(Reason.class);
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
        // The overrides that keep this group: they match it and have not expired.
        Set<String> hashes = members.stream().map(Member::hash).collect(Collectors.toSet());
        List<KeepOverride> keeping = overrides.stream()
                .filter(override -> override.matches(hashes) && !override.expired(now.toLocalDate()))
                .toList();
        if (!keeping.isEmpty()) {
            holding.add(Reason.KEEP_OVERRIDE);
        }
        if (libraryLinks > 0) {
            holding.add(Reason.LIBRARY_LINK);
        }
        if (members.stream().anyMatch(member -> !member.remaining().isZero())) {
            holding.add(Reason.MINIMUM_NOT_REACHED);
        }
        Reason reason = holding.stream().findFirst().orElse(Reason.ALL_MET);
        Optional<Forecast> forecast = switch (reason) {
            case MINIMUM_NOT_REACHED -> Optional.of(forecast(members, now));
            case KEEP_OVERRIDE -> endDate(keeping).map(Forecast::until);
            default -> Optional.empty();
        };
        return new GroupVerdict(members, reason.verdict(), reason, libraryLinks, files.size(), forecast,
                holding.stream().skip(1).toList(), missingFiles);
    }

    /** The date the overrides keep the group until: the latest end date; empty when one of them never ends. */
    private static Optional<LocalDate> endDate(List<KeepOverride> keeping) {
        if (keeping.stream().anyMatch(override -> override.until().isEmpty())) {
            return Optional.empty();
        }
        return keeping.stream().flatMap(override -> override.until().stream()).max(Comparator.naturalOrder());
    }

    private static boolean verifying(Torrent torrent) {
        return torrent.status() == Torrent.Status.CHECK_WAIT || torrent.status() == Torrent.Status.CHECKING;
    }

    private static Member member(Torrent torrent, TrackerRules trackers) {
        return new Member(torrent.hash(), torrent.name(), trackers.attribute(torrent.trackers(), torrent.isPrivate()),
                Duration.ofSeconds(torrent.secondsSeeding()), torrent.status());
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
