package io.github.ghostwritertje.seedkeeper.run;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.run.Deletion.Deleted;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
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
public record RunOutcome(ZonedDateTime started, ZonedDateTime ended, Status status, Optional<String> error,
        List<GroupVerdict> verdicts, List<Removal> removals) {

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
     * @param error    what went wrong; then no file was deleted, and the next run finds the rest still due
     * @param deleted  what was deleted on disk
     */
    public record Removal(Reason reason, List<Torrent> torrents, List<Torrent> removed, Optional<String> error,
            Deleted deleted) {

        public Removal {
            torrents = List.copyOf(torrents);
            removed = List.copyOf(removed);
        }
    }

    /** The space freed by all removals together, in bytes. */
    public long spaceFreed() {
        return removals.stream().mapToLong(removal -> removal.deleted().spaceFreed()).sum();
    }
}
