package io.github.ghostwritertje.seedkeeper;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.DateTimeException;
import java.time.ZoneId;

/** Refuses to start with a time zone that doesn't exist, so a typo shows at the start and not on the first page. */
@ApplicationScoped
public class TimeZoneCheck {

    void check(@Observes StartupEvent start, SeedkeeperConfig config) {
        try {
            ZoneId.of(config.timeZone());
        } catch (DateTimeException e) {
            throw new IllegalStateException("seedkeeper.time-zone is not a time zone: " + config.timeZone()
                    + " (use a tz database name such as Europe/Brussels)", e);
        }
    }
}
