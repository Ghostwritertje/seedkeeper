package io.github.ghostwritertje.seedkeeper.run;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.TrackerRules;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import io.github.ghostwritertje.seedkeeper.overrides.OverrideStore;
import io.github.ghostwritertje.seedkeeper.overrides.OverridesException;
import io.github.ghostwritertje.seedkeeper.transmission.Transmission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * Starts runs, one at a time (spec §5, step 1), and remembers the last one. Everything that starts a run goes
 * through {@link #run()}: Run now and the {@link ScheduledRun}.
 */
@ApplicationScoped
public class RunService {

    private static final Logger LOG = Logger.getLogger(RunService.class);

    private final Run run;
    private final OverrideStore overrides;
    private final RunMonitor monitor;
    private final Consumer<RunOutcome> ended;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile RunOutcome lastRun;

    @Inject
    RunService(
            Transmission transmission,
            SeedkeeperConfig config,
            OverrideStore overrides,
            RunMonitor monitor,
            Event<RunOutcome> ended) {
        this(
                new Run(
                        transmission,
                        TrackerRules.of(config),
                        config.downloadsRoot(),
                        config.libraryRoots(),
                        config.timeZone(),
                        overrides),
                overrides,
                monitor,
                ended::fire);
    }

    RunService(Run run, OverrideStore overrides, RunMonitor monitor) {
        this(run, overrides, monitor, outcome -> {});
    }

    /** @param ended told of every run that ends, while the run lock is still held */
    RunService(Run run, OverrideStore overrides, RunMonitor monitor, Consumer<RunOutcome> ended) {
        this.run = run;
        this.overrides = overrides;
        this.monitor = monitor;
        this.ended = ended;
    }

    /**
     * Does a run now, on the caller's thread, and returns when it has ended.
     *
     * @return what the run did; empty when a run is already running, and then nothing was done
     */
    public Optional<RunOutcome> run() {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        try {
            monitor.started();
            RunOutcome outcome = run.execute();
            monitor.ended(outcome);
            // What follows a run (spec §5, steps 4 and 5) belongs here, while the lock is still held.
            growOverrides(outcome);
            // The history observes the outcome (history/HistoryKeeper).
            ended.accept(outcome);
            lastRun = outcome;
            return Optional.of(outcome);
        } finally {
            running.set(false);
        }
    }

    /**
     * Spec §5, step 4: every override gets the hashes of the new members of the groups it matches. The groups a run
     * decided to remove are left out: they are gone, or will be. A file that can't be read or written now doesn't
     * fail the run, which is done; the next run adds the hashes.
     */
    private void growOverrides(RunOutcome outcome) {
        List<List<String>> groups = outcome.verdicts().stream()
                .filter(group -> group.verdict() != Verdict.REMOVE)
                .map(group ->
                        group.members().stream().map(GroupVerdict.Member::hash).toList())
                .toList();
        try {
            overrides.grow(groups);
        } catch (OverridesException e) {
            LOG.warnf("The hashes of new members were not added to the keep overrides: %s", e.getMessage());
        }
    }

    /** True while a run is running. */
    public boolean running() {
        return running.get();
    }

    /** The last run that ended since seedkeeper started; empty when there has been none. */
    public Optional<RunOutcome> lastRun() {
        return Optional.ofNullable(lastRun);
    }
}
