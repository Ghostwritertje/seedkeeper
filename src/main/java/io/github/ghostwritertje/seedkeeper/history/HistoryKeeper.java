package io.github.ghostwritertje.seedkeeper.history;

import io.github.ghostwritertje.seedkeeper.ModuleApi;
import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.history.History.HistoryException;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedRun;
import io.github.ghostwritertje.seedkeeper.run.RunMonitor;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

/**
 * Keeps the history going: makes the database at start, reads the last run from it so a restart doesn't forget it,
 * and writes every run that ends to it.
 */
@ModuleApi
@ApplicationScoped
public class HistoryKeeper {

    private static final Logger LOG = Logger.getLogger(HistoryKeeper.class);

    private final History history;
    private final Flyway flyway;
    private final Path dataDir;
    private final RunMonitor monitor;
    private volatile Optional<RecordedRun> lastRecordedRun = Optional.empty();

    HistoryKeeper(History history, Flyway flyway, SeedkeeperConfig config, RunMonitor monitor) {
        this.history = history;
        this.flyway = flyway;
        this.dataDir = config.dataDir();
        this.monitor = monitor;
    }

    /** The last run in the history when seedkeeper started; empty when there was none or it couldn't be read. */
    public Optional<RecordedRun> lastRecordedRun() {
        return lastRecordedRun;
    }

    /**
     * Makes the data dir when it isn't there and brings {@code seedkeeper.db} up to date; a database that is gone is
     * made anew, empty. seedkeeper doesn't start when that can't be done.
     */
    void start(@Observes StartupEvent event) {
        try {
            Files.createDirectories(dataDir);
            flyway.migrate();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "The data dir " + dataDir + " (seedkeeper.data-dir) can't be used: " + e
                            + ". seedkeeper keeps seedkeeper.db there: it must be a directory seedkeeper can write"
                            + " to",
                    e);
        }
        try {
            lastRecordedRun = history.lastRun();
            history.lastSuccessfulRunEnded().ifPresent(monitor::restoreLastSuccessfulRun);
        } catch (HistoryException e) {
            LOG.warn("The last run could not be read from the history: " + e.getMessage());
        }
    }

    /**
     * Fired by the run, while it still holds the run lock. A run that can't be written is not undone and doesn't
     * fail: the history is for display only.
     */
    void ended(@Observes RunOutcome outcome) {
        try {
            history.record(outcome);
        } catch (RuntimeException e) {
            LOG.error("The run that started at " + outcome.started() + " is missing from the history", e);
        }
    }
}
