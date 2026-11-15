package io.github.ghostwritertje.seedkeeper.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.decide.TrackerRules;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Status;
import io.github.ghostwritertje.seedkeeper.transmission.StubTransmission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The run lock and the memory of the last run (spec §5, step 1). */
@Timeout(30)
class RunServiceTest {

    @TempDir
    Path root;

    private final StubTransmission transmission = new StubTransmission();

    @Test
    void aRunGivesItsOutcomeAndIsRememberedAsTheLastRun() {
        RunService service = service();
        assertEquals(Optional.empty(), service.lastRun());

        Optional<RunOutcome> outcome = service.run();

        assertEquals(Status.SUCCEEDED, outcome.orElseThrow().status());
        assertEquals(outcome, service.lastRun());
        assertFalse(service.running());
    }

    @Test
    void aFailedRunIsRememberedTooAndTheNextRunCanStart() {
        RunService service = service();
        transmission.unreachable("Transmission does not answer");

        assertEquals(Status.FAILED, service.run().orElseThrow().status());
        assertEquals(Optional.of("Transmission does not answer"), service.lastRun().orElseThrow().error());
        assertTrue(service.run().isPresent(), "the lock was not released after a failed run");
    }

    @Test
    void whileARunIsRunningAnotherOneIsRefused() throws Exception {
        RunService service = service();
        CountDownLatch inTheRun = new CountDownLatch(1);
        CountDownLatch mayGoOn = new CountDownLatch(1);
        transmission.onSession(() -> {
            inTheRun.countDown();
            try {
                mayGoOn.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CompletableFuture<Optional<RunOutcome>> first = CompletableFuture.supplyAsync(service::run);
        assertTrue(inTheRun.await(10, TimeUnit.SECONDS));

        assertTrue(service.running());
        assertEquals(Optional.empty(), service.run(), "a second run started while the first was running");
        assertEquals(Optional.empty(), service.lastRun());

        mayGoOn.countDown();
        RunOutcome outcome = first.get(10, TimeUnit.SECONDS).orElseThrow();
        assertEquals(Status.SUCCEEDED, outcome.status());
        assertEquals(Optional.of(outcome), service.lastRun());
        assertFalse(service.running());
        assertTrue(service.run().isPresent(), "the lock was not released");
    }

    private RunService service() {
        TrackerRules trackers = new TrackerRules(List.of(), Duration.ofDays(30), Optional.empty());
        return new RunService(new Run(transmission, trackers, root, List.of(), ZoneOffset.UTC),
                new RunMonitor(new SimpleMeterRegistry()));
    }
}
