package io.github.ghostwritertje.seedkeeper.system;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmission;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs that start by themselves, with a schedule of every second. */
@QuarkusTest
@TestProfile(ScheduledRunTest.EverySecond.class)
@WithTestResource(FakeTransmissionResource.class)
class ScheduledRunTest {

    public static class EverySecond implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("seedkeeper.schedule", "* * * * * ?");
        }
    }

    @BeforeEach
    void noTorrents() {
        FakeTransmissionResource.fake().reset();
    }

    @Test
    void aRunStartsOnTheSchedule() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (torrentListsAsked() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }

        assertTrue(torrentListsAsked() > 0, "no run started by itself within 10 s");
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/system")
                .then()
                .statusCode(200)
                .body("lastRun.status", is("succeeded"))
                .body("nextRun", notNullValue());
    }

    @Test
    void aScheduledRunWhileAnotherIsRunningIsSkipped() throws Exception {
        // The run that comes first stands still at its first question to Transmission until the hold is released.
        FakeTransmission.Hold hold = FakeTransmissionResource.fake().holdNextSessionGet();
        assertTrue(hold.reached(10, TimeUnit.SECONDS), "no run started by itself within 10 s");
        try {
            FakeTransmissionResource.fake().reset();
            // Three more scheduled runs come due while the first one is still running.
            Thread.sleep(3_000);

            assertEquals(
                    0,
                    FakeTransmissionResource.fake().requests().size(),
                    "a scheduled run started while another was running");
        } finally {
            hold.release();
        }
    }

    /** How many times Transmission was asked for its torrents: once per run that got that far. */
    private static long torrentListsAsked() {
        return FakeTransmissionResource.fake().requests().stream()
                .filter(request -> request.body().contains("\"method\":\"torrent_get\""))
                .count();
    }
}
