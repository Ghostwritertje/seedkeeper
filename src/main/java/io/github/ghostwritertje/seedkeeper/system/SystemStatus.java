package io.github.ghostwritertje.seedkeeper.system;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

/**
 * What the System view and {@code GET /api/v1/system} show.
 *
 * @param running true while a run is running
 * @param lastRun the last run that ended; before the first run since the start, the last one of the run history;
 *                null when there has been none
 * @param nextRun when the next scheduled run starts: ISO 8601, to the second, with the offset of the configured time
 *                zone; null when there is none
 */
@RegisterForReflection
record SystemStatus(String version, TransmissionStatus transmission, boolean running, LastRun lastRun, String nextRun) {

    /**
     * Whether seedkeeper can work with Transmission.
     *
     * @param reachable Transmission answered
     * @param version   as Transmission reports it; null when it didn't say
     * @param reason    why seedkeeper can't work with this Transmission; null when it can
     * @param incompleteDirWarning what is worth knowing about Transmission's incomplete dir, which seedkeeper deletes
     *                             in: it is enabled and outside the downloads root, or it is {@code /}; null when
     *                             there is nothing to tell
     */
    @RegisterForReflection
    public record TransmissionStatus(boolean reachable, String version, String reason, String incompleteDirWarning) {

        public boolean usable() {
            return reason == null;
        }
    }

    /**
     * What the last run did.
     *
     * @param started         ISO 8601, to the second, with the offset of the configured time zone
     * @param ended           like {@code started}
     * @param status          {@code succeeded} or {@code failed}
     * @param error           the one reason of a failed run; null when it succeeded
     * @param groupsRemoved   the removals that were carried out in full
     * @param torrentsRemoved the torrents Transmission removed, also those of a removal that then went wrong
     * @param filesDeleted    the files and symlinks that were deleted
     * @param spaceFreedBytes the size of the deleted files that were the last link of their data
     * @param removalErrors   one line per removal that went wrong or left something undeleted
     */
    @RegisterForReflection
    public record LastRun(
            String started,
            String ended,
            String status,
            String error,
            int groupsRemoved,
            int torrentsRemoved,
            int filesDeleted,
            long spaceFreedBytes,
            List<String> removalErrors) {

        public boolean succeeded() {
            return error == null;
        }
    }
}
