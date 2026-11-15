package io.github.ghostwritertje.seedkeeper.decide;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict.Member;
import io.github.ghostwritertje.seedkeeper.snapshot.FileId;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.SnapshotTorrent;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.WantedFile;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The rules (spec §3.5): a pure function from a snapshot to a verdict per group. Two calls on the same snapshot with
 * the same now give the same verdicts.
 *
 * <p>So far the rules are: unmanaged, not finished, library link, minimum not reached, all met. Each holds for the
 * group when it holds for any member, so a group is removed only when every member has met its own tracker's
 * minimum.
 */
public final class Decide {

    private Decide() {
    }

    /**
     * @param now the moment of the snapshot, in the configured time zone; the forecast counts from it
     */
    public static List<GroupVerdict> decide(Snapshot snapshot, TrackerRules trackers, ZonedDateTime now) {
        return snapshot.groups().stream().map(group -> decide(group, snapshot, trackers, now)).toList();
    }

    private static GroupVerdict decide(List<SnapshotTorrent> group, Snapshot snapshot, TrackerRules trackers,
            ZonedDateTime now) {
        List<Member> members = group.stream().map(member -> member(member.torrent(), trackers)).toList();

        // A file that several members share is one file; a file that resolves to nothing is counted per member.
        Set<FileId> seen = new HashSet<>();
        List<WantedFile> files = group.stream()
                .flatMap(member -> member.wantedFiles().stream())
                .filter(file -> file.id().map(seen::add).orElse(true))
                .toList();
        int libraryLinks = (int) files.stream().filter(snapshot::hasLibraryLink).count();

        Reason reason;
        if (members.stream().anyMatch(member -> member.tracker().isEmpty())) {
            reason = Reason.UNMANAGED;
        } else if (group.stream().anyMatch(member -> !member.torrent().finished())) {
            reason = Reason.NOT_FINISHED;
        } else if (libraryLinks > 0) {
            reason = Reason.LIBRARY_LINK;
        } else if (members.stream().anyMatch(member -> !member.remaining().isZero())) {
            reason = Reason.MINIMUM_NOT_REACHED;
        } else {
            reason = Reason.ALL_MET;
        }
        Optional<Forecast> forecast = reason == Reason.MINIMUM_NOT_REACHED
                ? Optional.of(forecast(members, now))
                : Optional.empty();
        return new GroupVerdict(members, reason.verdict(), reason, libraryLinks, files.size(), forecast);
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
