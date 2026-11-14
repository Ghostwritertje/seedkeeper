package io.github.ghostwritertje.seedkeeper.decide;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict.Member;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.SnapshotTorrent;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The rules (spec §3.5): a pure function from a snapshot to a verdict per group. Two calls on the same snapshot give
 * the same verdicts.
 *
 * <p>So far every torrent is a group of its own, and the rules are: unmanaged, not finished, library link, minimum
 * not reached, all met.
 */
public final class Decide {

    private Decide() {
    }

    public static List<GroupVerdict> decide(Snapshot snapshot, TrackerRules trackers) {
        return snapshot.torrents().stream().map(torrent -> decide(torrent, snapshot, trackers)).toList();
    }

    private static GroupVerdict decide(SnapshotTorrent member, Snapshot snapshot, TrackerRules trackers) {
        Torrent torrent = member.torrent();
        Optional<Tracker> tracker = trackers.attribute(torrent.trackers(), torrent.isPrivate());
        Duration seedTime = Duration.ofSeconds(torrent.secondsSeeding());
        int libraryLinks = (int) member.wantedFiles().stream().filter(snapshot::hasLibraryLink).count();

        Reason reason;
        if (tracker.isEmpty()) {
            reason = Reason.UNMANAGED;
        } else if (!torrent.finished()) {
            reason = Reason.NOT_FINISHED;
        } else if (libraryLinks > 0) {
            reason = Reason.LIBRARY_LINK;
        } else if (seedTime.compareTo(tracker.get().minimumSeedTime()) < 0) {
            reason = Reason.MINIMUM_NOT_REACHED;
        } else {
            reason = Reason.ALL_MET;
        }
        Member result = new Member(torrent.hash(), torrent.name(), tracker, seedTime, torrent.status());
        return new GroupVerdict(List.of(result), reason.verdict(), reason, libraryLinks, member.wantedFiles().size());
    }
}
