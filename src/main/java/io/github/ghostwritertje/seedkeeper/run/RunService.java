package io.github.ghostwritertje.seedkeeper.run;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.TrackerRules;
import io.github.ghostwritertje.seedkeeper.transmission.Transmission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts runs, one at a time (spec §5, step 1), and remembers the last one. Everything that starts a run goes
 * through {@link #run()}: Run now, and later the schedule.
 */
@ApplicationScoped
public class RunService {

    private final Run run;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile RunOutcome lastRun;

    @Inject
    RunService(Transmission transmission, SeedkeeperConfig config) {
        this(new Run(transmission, TrackerRules.of(config), config.downloadsRoot(), config.libraryRoots(),
                config.timeZone()));
    }

    RunService(Run run) {
        this.run = run;
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
            RunOutcome outcome = run.execute();
            // What follows a run (spec §5, steps 4 and 5) belongs here, while the lock is still held.
            lastRun = outcome;
            return Optional.of(outcome);
        } finally {
            running.set(false);
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
