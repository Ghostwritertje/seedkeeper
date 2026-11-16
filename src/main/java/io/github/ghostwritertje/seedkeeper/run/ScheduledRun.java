package io.github.ghostwritertje.seedkeeper.run;

import io.github.ghostwritertje.seedkeeper.ModuleApi;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The scheduled run: starts a run on the cron of {@code seedkeeper.schedule}, in {@code seedkeeper.time-zone}. One
 * that finds a run running is skipped, not queued (spec §5, step 1).
 */
@ModuleApi
@ApplicationScoped
public class ScheduledRun {

    private static final Logger LOG = Logger.getLogger(ScheduledRun.class);
    private static final String JOB = "scheduled-run";

    private final RunService runs;
    private final Scheduler scheduler;

    ScheduledRun(RunService runs, Scheduler scheduler) {
        this.runs = runs;
        this.scheduler = scheduler;
    }

    @Scheduled(
            identity = JOB,
            cron = "${seedkeeper.schedule}",
            timeZone = "${seedkeeper.time-zone}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @RunOnVirtualThread
    void start() {
        if (runs.run().isEmpty()) {
            LOG.info("Skipped the scheduled run: a run is already running");
        }
    }

    /** When the next scheduled run starts; empty when there is none. */
    public Optional<Instant> next() {
        return Optional.ofNullable(scheduler.getScheduledJob(JOB)).map(Trigger::getNextFireTime);
    }
}
