package io.github.ghostwritertje.seedkeeper.decide;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * When a group kept for <em>minimum not reached</em> is expected to have met every minimum, assuming continuous
 * seeding (spec §3.6).
 *
 * @param date the expected date, in the configured time zone; empty when the member that sets it is paused, so the
 *             clock is stopped
 */
public record Forecast(Optional<ZonedDateTime> date) {

    public boolean clockStopped() {
        return date.isEmpty();
    }
}
