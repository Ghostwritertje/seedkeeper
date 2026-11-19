package io.github.ghostwritertje.seedkeeper.decide;

import io.github.ghostwritertje.seedkeeper.ModuleApi;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict.Member;
import io.github.ghostwritertje.seedkeeper.keep.KeepOverride;
import io.github.ghostwritertje.seedkeeper.snapshot.FileId;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.SnapshotTorrent;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.WantedFile;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The rules (spec §3.5): a pure function from a snapshot to a verdict per group. Two calls on the same snapshot with
 * the same now give the same verdicts.
 *
 * <p>The rules are those of {@link Reason}, in its order. Each holds
 * for the group when it holds for any member, so a group is removed only when every member has met its own tracker's
 * minimum. The first that holds decides the verdict, the others that hold are its secondary reasons.
 */
@ModuleApi
public final class Decide {

    /** Transmission's error number for a local error, such as files it can't read or write. */
    private static final int LOCAL_ERROR = 3;

    private Decide() {}

    /** The verdicts when there are no keep overrides. */
    public static List<GroupVerdict> decide(Snapshot snapshot, TrackerRules trackers, ZonedDateTime now) {
        return decide(snapshot, trackers, List.of(), now);
    }

    /**
     * One verdict per group, in the order of each group's first member in the snapshot.
     *
     * <p>An unregistered torrent that is not verifying and is attributed to a managed tracker (the public entry
     * included, when configured) leaves its group (rule 1): it gets a verdict of its own,
     * {@link Verdict#REMOVE} with {@link Reason#UNREGISTERED} and itself as the only member. The groups are built
     * from the other torrents, so the rest of its group is judged without it. To carry out the verdicts, remove every
     * member of every {@link Verdict#REMOVE} verdict, and nothing else. An unregistered torrent that is unmanaged
     * stays in its group, which is left alone as unmanaged, with {@link Reason#UNREGISTERED} as a secondary reason.
     *
     * @param overrides the keep overrides, as read at the start of the run; expired and stale ones have no effect,
     *                  and none keeps an unregistered torrent
     * @param now       the moment of the snapshot, in the configured time zone; the forecast counts from it and the
     *                  end date of an override is a date in its zone
     */
    public static List<GroupVerdict> decide(
            Snapshot snapshot, TrackerRules trackers, List<KeepOverride> overrides, ZonedDateTime now) {
        IdentityHashMap<SnapshotTorrent, Integer> position = new IdentityHashMap<>();
        snapshot.torrents().forEach(torrent -> position.put(torrent, position.size()));

        Map<Boolean, List<SnapshotTorrent>> byRule1 = snapshot.torrents().stream()
                .collect(Collectors.partitioningBy(member -> leavesItsGroup(member.torrent(), trackers)));
        // partitioningBy always holds both keys.
        Snapshot rest = new Snapshot(Objects.requireNonNull(byRule1.get(false)), snapshot.library());

        // The cross-seed group of every torrent, as the info hashes of its torrents: what a keep override matches.
        IdentityHashMap<SnapshotTorrent, List<String>> crossSeedGroups = new IdentityHashMap<>();
        for (List<SnapshotTorrent> group : snapshot.groups()) {
            List<String> hashes =
                    group.stream().map(member -> member.torrent().hash()).toList();
            group.forEach(member -> crossSeedGroups.put(member, hashes));
        }

        Map<Integer, GroupVerdict> verdicts = new TreeMap<>();
        for (SnapshotTorrent alone : Objects.requireNonNull(byRule1.get(true))) {
            verdicts.put(
                    position.get(alone),
                    decide(
                            List.of(alone),
                            true,
                            Objects.requireNonNull(crossSeedGroups.get(alone)),
                            snapshot,
                            trackers,
                            overrides,
                            now));
        }
        for (List<SnapshotTorrent> group : rest.groups()) {
            SnapshotTorrent first = group.getFirst();
            verdicts.put(
                    position.get(first),
                    decide(
                            group,
                            false,
                            Objects.requireNonNull(crossSeedGroups.get(first)),
                            snapshot,
                            trackers,
                            overrides,
                            now));
        }
        return List.copyOf(verdicts.values());
    }

    /** Rule 1: unregistered, not verifying and attributed to a managed tracker. */
    private static boolean leavesItsGroup(Torrent torrent, TrackerRules trackers) {
        return unregistered(torrent) && attribute(torrent, trackers).isPresent();
    }

    /** Unregistered and not verifying: removed when managed, a secondary reason when not. */
    private static boolean unregistered(Torrent torrent) {
        return !verifying(torrent)
                && TrackerError.of(torrent.error(), torrent.errorString())
                        .filter(TrackerError::unregistered)
                        .isPresent();
    }

    private static Optional<Tracker> attribute(Torrent torrent, TrackerRules trackers) {
        return trackers.attribute(torrent.trackers(), torrent.isPrivate());
    }

    /**
     * @param unregistered   true when the group is one unregistered torrent that left its group
     * @param crossSeedGroup the info hashes of the torrents of the group's cross-seed group
     */
    private static GroupVerdict decide(
            List<SnapshotTorrent> group,
            boolean unregistered,
            List<String> crossSeedGroup,
            Snapshot snapshot,
            TrackerRules trackers,
            List<KeepOverride> overrides,
            ZonedDateTime now) {
        List<Member> members =
                group.stream().map(member -> member(member.torrent(), trackers)).toList();

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
        // In a group, an unregistered member is unmanaged (a managed one left it): shown, but never the reason.
        if (unregistered || group.stream().anyMatch(member -> unregistered(member.torrent()))) {
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
        // The overrides that keep this group: they name a torrent of its cross-seed group and have not expired.
        List<KeepOverride> keeping = overrides.stream()
                .filter(override -> override.matches(crossSeedGroup) && !override.expired(now.toLocalDate()))
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
        Reason reason = holding.stream()
                .filter(held -> unregistered || held != Reason.UNREGISTERED)
                .findFirst()
                .orElse(Reason.ALL_MET);
        Optional<Forecast> forecast = switch (reason) {
            case MINIMUM_NOT_REACHED -> Optional.of(forecast(members, now));
            case KEEP_OVERRIDE -> endDate(keeping).map(Forecast::until);
            default -> Optional.empty();
        };
        return new GroupVerdict(
                members,
                reason.verdict(),
                reason,
                libraryLinks,
                files.size(),
                forecast,
                holding.stream().filter(held -> held != reason).toList(),
                missingFiles,
                crossSeedGroup);
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
        return new Member(
                torrent.hash(),
                torrent.name(),
                attribute(torrent, trackers),
                Duration.ofSeconds(torrent.secondsSeeding()),
                torrent.status(),
                TrackerError.of(torrent.error(), torrent.errorString()));
    }

    /**
     * Now plus the largest remaining seed time over the members (spec §3.6). No date when a member that sets it is
     * paused: its seed time stands still.
     */
    private static Forecast forecast(List<Member> members, ZonedDateTime now) {
        Duration largest =
                members.stream().map(Member::remaining).max(Duration::compareTo).orElse(Duration.ZERO);
        boolean setterPaused = members.stream()
                .anyMatch(member -> member.remaining().equals(largest) && member.status() == Torrent.Status.STOPPED);
        return new Forecast(setterPaused ? Optional.empty() : Optional.of(now.plus(largest)));
    }
}
