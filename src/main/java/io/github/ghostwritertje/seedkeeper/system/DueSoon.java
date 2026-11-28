package io.github.ghostwritertje.seedkeeper.system;

import io.github.ghostwritertje.seedkeeper.decide.Forecast;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A kept verdict group whose forecast falls soon: by the next run, or within {@link #WINDOW} when that is later.
 *
 * @param group the verdict group
 * @param when  when it may be removed: the forecast date, or the end of the last day of a keep override
 */
record DueSoon(GroupVerdict group, ZonedDateTime when) {

    /** How far ahead the overview looks at the least, whenever the next run is. */
    static final Duration WINDOW = Duration.ofDays(7);

    /** Until when a forecast is soon: the next run, or {@link #WINDOW} from now when that is later or there is none. */
    static Instant horizon(Optional<Instant> nextRun, Instant now) {
        Instant window = now.plus(WINDOW);
        return nextRun.filter(next -> next.isAfter(window)).orElse(window);
    }

    /** The kept verdict groups whose forecast falls by the horizon, soonest first. */
    static List<DueSoon> of(List<GroupVerdict> groups, Instant horizon, ZoneId zone) {
        return groups.stream()
                .filter(group -> group.verdict() == Verdict.KEEP)
                .flatMap(group -> group.forecast()
                        .flatMap(forecast -> end(forecast, zone))
                        .filter(when -> !when.toInstant().isAfter(horizon))
                        .map(when -> new DueSoon(group, when))
                        .stream())
                .sorted(Comparator.comparing(DueSoon::when))
                .toList();
    }

    /** The forecast date, or the end of the last day of a keep override, which is inclusive; empty for a stopped clock. */
    private static Optional<ZonedDateTime> end(Forecast forecast, ZoneId zone) {
        return forecast.until()
                .map(until -> until.plusDays(1).atStartOfDay(zone))
                .or(forecast::date);
    }
}
