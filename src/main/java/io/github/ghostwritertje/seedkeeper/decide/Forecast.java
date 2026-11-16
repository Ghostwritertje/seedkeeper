package io.github.ghostwritertje.seedkeeper.decide;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Until when a kept group is expected to be kept (spec §3.6). For <em>minimum not reached</em>: when every minimum
 * is expected to be met, assuming continuous seeding. For <em>keep override</em>: the end date of the override.
 *
 * @param date  the expected date, in the configured time zone; empty when the member that sets it is paused, so the
 *              clock is stopped, and for a keep override
 * @param until the end date (inclusive) of the keep override that keeps the group; empty for every other reason
 */
public record Forecast(Optional<ZonedDateTime> date, Optional<LocalDate> until) {

    /** The forecast of a group kept for <em>minimum not reached</em>. */
    public Forecast(Optional<ZonedDateTime> date) {
        this(date, Optional.empty());
    }

    /** The forecast of a group kept by a keep override with this end date. */
    public static Forecast until(LocalDate endDate) {
        return new Forecast(Optional.empty(), Optional.of(endDate));
    }

    public boolean clockStopped() {
        return date.isEmpty() && until.isEmpty();
    }
}
