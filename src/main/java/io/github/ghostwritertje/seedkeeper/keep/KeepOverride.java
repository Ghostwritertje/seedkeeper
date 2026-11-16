package io.github.ghostwritertje.seedkeeper.keep;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A keep override (spec §7): a manual instruction never to remove a group. It can only prevent a removal.
 *
 * @param id      names the override in the file; unique
 * @param hashes  the info hashes of the members, lower-case; a run adds new members and never removes one
 * @param note    why; empty text when there is none
 * @param until   the end date, inclusive, in the configured time zone; empty when it never ends
 * @param created when it was set; empty when the file doesn't say
 */
public record KeepOverride(
        String id, List<String> hashes, String note, Optional<LocalDate> until, Optional<OffsetDateTime> created) {

    public KeepOverride {
        hashes = List.copyOf(hashes);
    }

    /** True when the group with these info hashes has at least one of this override's hashes. */
    public boolean matches(Collection<String> groupHashes) {
        return hashes.stream().anyMatch(groupHashes::contains);
    }

    /**
     * True when the override is past its end date. On the end date itself it still holds.
     *
     * @param today the date in the configured time zone
     */
    public boolean expired(LocalDate today) {
        return until.filter(today::isAfter).isPresent();
    }

    /** This override with the missing ones of these hashes added at the end; itself when it has them all. */
    public KeepOverride withHashes(Collection<String> more) {
        List<String> added =
                more.stream().distinct().filter(hash -> !hashes.contains(hash)).toList();
        if (added.isEmpty()) {
            return this;
        }
        return new KeepOverride(
                id, Stream.concat(hashes.stream(), added.stream()).toList(), note, until, created);
    }
}
