package io.github.ghostwritertje.seedkeeper.run;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Removal;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Status;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * What a run tells the outside (spec §10): the metrics on {@code /q/metrics} and one log line per run start and
 * end, removal, deleted file and error. Every line carries its kind in the MDC key {@value #EVENT}, which the JSON
 * log has under {@code mdc}.
 */
@ApplicationScoped
@Startup // the metrics are there from the start, not from the first run
public class RunMonitor {

    /**
     * The MDC key of a line's kind: {@code run-start}, {@code run-end}, {@code removal}, {@code deleted-file},
     * {@code refused-path}.
     */
    static final String EVENT = "event";

    private static final Logger LOG = Logger.getLogger(RunMonitor.class);

    private final Map<Status, Counter> runs = new EnumMap<>(Status.class);
    private final Counter removedGroups;
    private final Counter spaceFreed;
    private final AtomicLong lastSuccessfulRun = new AtomicLong();
    private final Map<Reason, AtomicLong> groups = new EnumMap<>(Reason.class);

    RunMonitor(MeterRegistry registry) {
        // Everything is registered up front, so each metric is there, at zero, before the first run.
        for (Status status : Status.values()) {
            runs.put(
                    status,
                    Counter.builder("seedkeeper.runs")
                            .description("Runs that ended, by status")
                            .tag("status", status.code())
                            .register(registry));
        }
        removedGroups = Counter.builder("seedkeeper.removed.groups")
                .description("Removals carried out in full: a whole group, or the one member that left alone")
                .register(registry);
        spaceFreed = Counter.builder("seedkeeper.space.freed")
                .description("Space freed by deleting files")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder("seedkeeper.last.successful.run.timestamp", lastSuccessfulRun, AtomicLong::doubleValue)
                .description("When the last run that succeeded ended, in seconds since the epoch; 0 when none has")
                .baseUnit("seconds")
                .register(registry);
        for (Reason reason : Reason.values()) {
            AtomicLong count = new AtomicLong();
            groups.put(reason, count);
            Gauge.builder("seedkeeper.groups", count, AtomicLong::doubleValue)
                    .description("Groups by the verdict and reason the last run with verdicts gave them")
                    .tag("verdict", reason.verdict().code())
                    .tag("reason", reason.code())
                    .register(registry);
        }
    }

    /** Sets the last successful run from the history at start, unless a run since has set a later one. */
    public void restoreLastSuccessfulRun(Instant ended) {
        lastSuccessfulRun.accumulateAndGet(ended.getEpochSecond(), Math::max);
    }

    /** A run has taken the lock. */
    void started() {
        log("run-start", Logger.Level.INFO, "Run started");
    }

    /** A run has ended: counts it and logs what it did. */
    void ended(RunOutcome outcome) {
        for (Removal removal : outcome.removals()) {
            logRemoval(removal);
        }
        int carriedOut = outcome.groupsRemoved();
        // The constructor registers a counter for every status.
        Objects.requireNonNull(runs.get(outcome.status())).increment();
        removedGroups.increment(carriedOut);
        // A double holds every byte count up to 8 PiB exactly; no run frees more.
        spaceFreed.increment((double) outcome.spaceFreed());
        if (outcome.status() == Status.SUCCEEDED) {
            lastSuccessfulRun.set(outcome.ended().toEpochSecond());
            // A failed run has no verdicts: then the groups stay as the last run that had them left them.
            Map<Reason, Long> counts = outcome.verdicts().stream()
                    .collect(Collectors.groupingBy(GroupVerdict::reason, Collectors.counting()));
            groups.forEach((reason, count) -> count.set(counts.getOrDefault(reason, 0L)));
            log(
                    "run-end",
                    Logger.Level.INFO,
                    "Run ended: succeeded, %d groups, %d removals carried out, %d failed, %d bytes freed"
                            .formatted(
                                    outcome.verdicts().size(),
                                    carriedOut,
                                    outcome.removals().size() - carriedOut,
                                    outcome.spaceFreed()));
        } else {
            log(
                    "run-end",
                    Logger.Level.ERROR,
                    "Run ended: failed: " + outcome.error().orElse("no reason given"));
        }
    }

    private static void logRemoval(Removal removal) {
        String names = removal.torrents().stream().map(Torrent::name).collect(Collectors.joining(", "));
        if (removal.error().isPresent()) {
            log(
                    "removal",
                    Logger.Level.ERROR,
                    "Removal of %s (%s) failed after %d of %d torrents, no files deleted: %s"
                            .formatted(
                                    names,
                                    removal.reason().code(),
                                    removal.removed().size(),
                                    removal.torrents().size(),
                                    removal.error().get()));
            return;
        }
        log(
                "removal",
                Logger.Level.INFO,
                "Removed %s (%s): %d torrents, %d files deleted, %d bytes freed"
                        .formatted(
                                names,
                                removal.reason().code(),
                                removal.removed().size(),
                                removal.deleted().files().size(),
                                removal.deleted().spaceFreed()));
        for (Path file : removal.deleted().files()) {
            log("deleted-file", Logger.Level.INFO, "Deleted " + file);
        }
        for (Path refused : removal.deleted().refused()) {
            log("refused-path", Logger.Level.ERROR, "Refused to delete " + refused);
        }
    }

    private static void log(String event, Logger.Level level, String message) {
        MDC.put(EVENT, event);
        try {
            LOG.log(level, message);
        } finally {
            MDC.remove(EVENT);
        }
    }
}
