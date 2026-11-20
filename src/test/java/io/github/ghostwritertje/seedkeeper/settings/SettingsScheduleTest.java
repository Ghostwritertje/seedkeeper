package io.github.ghostwritertje.seedkeeper.settings;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Run card of the Settings view with a daily schedule: the schedule in plain words, the cron expression after it. */
@QuarkusTest
@TestProfile(SettingsScheduleTest.Daily.class)
class SettingsScheduleTest {

    private static final String RUN = "html.body.main.section.find { it.@id == 'run' }.dl";

    public static class Daily implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config =
                    TestSecrets.with("seedkeeper.transmission.url", "http://127.0.0.1:9/transmission/rpc");
            config.put("seedkeeper.schedule", "0 30 4 * * ?");
            config.put("seedkeeper.time-zone", "UTC");
            config.put("seedkeeper.downloads-root", "/downloads");
            config.put("seedkeeper.library-roots", "/library");
            config.put("seedkeeper.data-dir", DATA_DIR.toString());
            return config;
        }
    }

    // A fixed path, the same in the profile's and the test's classloader; seedkeeper creates it on start.
    private static final Path DATA_DIR = Path.of(System.getProperty("java.io.tmpdir"), "seedkeeper-settings-schedule");

    @Test
    void aDailyScheduleIsInPlainWordsWithTheCronExpressionAndTheNextRun() {
        Authenticated.asUser()
                .when()
                .get("/settings")
                .then()
                .statusCode(200)
                .body(RUN + ".dt", contains("Schedule", "Time zone", "Next run"))
                .body(RUN + ".dd[0]", is("Every day at 04:30 (0 30 4 * * ?)"))
                .body(RUN + ".dd[1]", is("UTC"))
                .body(RUN + ".dd[2].time", matchesPattern("\\d{4}-\\d{2}-\\d{2} 04:30:00"))
                .body(RUN + ".dd[2].time.@datetime", matchesPattern("\\d{4}-\\d{2}-\\d{2}T04:30:00Z"));
    }
}
