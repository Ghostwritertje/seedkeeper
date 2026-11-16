package io.github.ghostwritertje.seedkeeper.run;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * What one run did (spec §5).
 *
 * @param started  in the configured time zone; also the "now" the verdicts were decided at
 * @param error    the one reason a failed run has; empty when it succeeded
 * @param verdicts the verdict on every group of the snapshot; empty when the run failed
 * @param removals what was carried out, one per removal that was due, in the order of the verdicts
 */
public record RunOutcome(
        ZonedDateTime started,
        ZonedDateTime ended,
        Status status,
        Optional<String> error,
        List<GroupVerdict> verdicts,
        List<Removal> removals) {

    public RunOutcome {
        verdicts = List.copyOf(verdicts);
        removals = List.copyOf(removals);
    }

    /**
     * A run fails when there are no verdicts: Transmission can't be used or the snapshot can't be built. A removal
     * that went wrong doesn't fail the run; it carries its own error.
     */
    public enum Status {
        SUCCEEDED("succeeded"),
        FAILED("failed");

        private final String code;

        Status(String code) {
            this.code = code;
        }

        /** The kebab-case code for the JSON API and the history. */
        public String code() {
            return code;
        }
    }

    /**
     * One removal: the torrents that had to leave together, and how far it got.
     *
     * @param reason   why they had to leave
     * @param torrents the torrents that were due: every member of a group, or the one member that leaves alone
     * @param removed  the ones Transmission removed; all of {@code torrents} unless there is an error
     * @param error    what went wrong; then no file was deleted. When a torrent could not be removed, the next run
     *                 finds the rest still due; when every one was but the torrent list could not be read again,
     *                 what they left on disk is an orphan
     * @param deleted  what was deleted on disk
     */
    public record Removal(
            Reason reason, List<Torrent> torrents, List<Torrent> removed, Optional<String> error, Deleted deleted) {

        public Removal {
            torrents = List.copyOf(torrents);
            removed = List.copyOf(removed);
        }
    }

    /**
     * What the deletion of one removal did.
     *
     * @param files      the files and symlinks that were deleted, in the order they went
     * @param spaceFreed the size of every deleted file that was the last link of its data, in bytes
     * @param refused    the paths that were due but not deleted: outside the downloads root and incomplete dir, under
     *                   a library root, while a root could not be found, or the filesystem said no
     */
    public record Deleted(List<Path> files, long spaceFreed, List<Path> refused) {

        public Deleted {
            files = List.copyOf(files);
            refused = List.copyOf(refused);
        }

        static final Deleted NOTHING = new Deleted(List.of(), 0, List.of());
    }

    /** The removals carried out in full. */
    public int groupsRemoved() {
        return (int)
                removals.stream().filter(removal -> removal.error().isEmpty()).count();
    }

    /** The torrents Transmission removed, also those of a removal that then went wrong. */
    public int torrentsRemoved() {
        return removals.stream().mapToInt(removal -> removal.removed().size()).sum();
    }

    /** The files and symlinks deleted by all removals together. */
    public int filesDeleted() {
        return removals.stream()
                .mapToInt(removal -> removal.deleted().files().size())
                .sum();
    }

    /** The space freed by all removals together, in bytes. */
    public long spaceFreed() {
        return removals.stream()
                .mapToLong(removal -> removal.deleted().spaceFreed())
                .sum();
    }
}
