package io.github.ghostwritertje.seedkeeper.system;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.history.History;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Deleted;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Removal;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Status;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

/**
 * After a restart, the System view, the API and the metrics know the last run from the run history: seedkeeper
 * starts here on a data dir whose history has a successful run with a removal that went wrong, and after it a failed
 * run.
 */
@QuarkusTest
@TestProfile(LastRunAfterRestartTest.RanBefore.class)
class LastRunAfterRestartTest {

    private static final ZonedDateTime SUCCEEDED = ZonedDateTime.of(2026, 11, 16, 6, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime FAILED = SUCCEEDED.plusHours(1);
    private static final String RUNS = "html.body.main.section.find { it.@id == 'runs' }";

    public static class RanBefore implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config =
                    TestSecrets.with("seedkeeper.transmission.url", "http://127.0.0.1:9/transmission/rpc");
            config.put("seedkeeper.library-roots", "/library");
            config.put("seedkeeper.time-zone", "UTC");
            config.put("seedkeeper.data-dir", dataDirWithHistory().toString());
            return config;
        }
    }

    /** A data dir with the history a seedkeeper that ran before left behind. */
    static Path dataDirWithHistory() {
        try {
            Path dataDir = Files.createTempDirectory("seedkeeper-restart");
            SQLiteDataSource database = new SQLiteDataSource();
            database.setUrl("jdbc:sqlite:" + dataDir.resolve("seedkeeper.db"));
            Flyway.configure()
                    .dataSource(database)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            History history = new History(database);
            Torrent movie = new Torrent(
                    "a1", "Some.Movie", Torrent.Status.SEEDING, 1, 0, "", 0, true, "/downloads", List.of(), List.of());
            Torrent busy = new Torrent(
                    "b2", "Busy.Movie", Torrent.Status.SEEDING, 1, 0, "", 0, true, "/downloads", List.of(), List.of());
            history.record(new RunOutcome(
                    SUCCEEDED,
                    SUCCEEDED.plusSeconds(4),
                    Status.SUCCEEDED,
                    Optional.empty(),
                    List.of(),
                    List.of(
                            new Removal(
                                    Reason.ALL_MET,
                                    List.of(movie),
                                    List.of(movie),
                                    Optional.empty(),
                                    new Deleted(
                                            List.of(Path.of("/downloads/Some.Movie/movie.mkv")),
                                            1024,
                                            List.of(Path.of("/elsewhere/a"), Path.of("/elsewhere/b")))),
                            new Removal(
                                    Reason.ALL_MET,
                                    List.of(busy),
                                    List.of(),
                                    Optional.of("torrent is busy"),
                                    new Deleted(List.of(), 0, List.of())))));
            history.record(new RunOutcome(
                    FAILED,
                    FAILED.plusSeconds(1),
                    Status.FAILED,
                    Optional.of("Transmission does not answer"),
                    List.of(),
                    List.of()));
            return dataDir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theApiGivesTheLastRunOfTheHistory() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/system")
                .then()
                .statusCode(200)
                .body("lastRun.started", is("2026-11-16T07:00:00Z"))
                .body("lastRun.ended", is("2026-11-16T07:00:01Z"))
                .body("lastRun.status", is("failed"))
                .body("lastRun.error", is("Transmission does not answer"))
                .body("lastRun.groupsRemoved", is(0))
                .body("lastRun.removalErrors", empty());
    }

    @Test
    void thePageGivesTheLastRunOfTheHistory() {
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .statusCode(200)
                .body(RUNS + ".dl.dd[1]", is("Failed"))
                .body(
                        RUNS + ".p.find { it.@role == 'alert' }",
                        is("Transmission does not answer"));
    }

    @Test
    void thePageGivesWhenTheLastRunStartedHowLongAgoAndHowLongItTook() {
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .statusCode(200)
                .body(
                        RUNS + ".dl.dt.collect { it.text() }",
                        contains(
                                "Status",
                                "Last run",
                                "Started",
                                "Took",
                                "Removed",
                                "Files deleted",
                                "Space freed",
                                "Next run"))
                .body(RUNS + ".dl.dd[2].time.@datetime", is("2026-11-16T07:00:00Z"))
                .body(RUNS + ".dl.dd[2].time.@title", is("2026-11-16 07:00:00 UTC"))
                .body(RUNS + ".dl.dd[2].time", is("2026-11-16 07:00:00"))
                .body(RUNS + ".dl.dd[2].span", matchesPattern("\\d+d( \\d+h)? ago"))
                .body(RUNS + ".dl.dd[3]", is("1s"))
                .body(RUNS + ".p.find { it.@id == 'time-zone' }", is("Times are in UTC."));
    }

    @Test
    void theNextRunIsATimeWithHowLongUntilIt() {
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .statusCode(200)
                .body(RUNS + ".dl.dd[-1].time.@datetime", matchesPattern("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ"))
                .body(RUNS + ".dl.dd[-1].time", matchesPattern("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d"))
                .body(RUNS + ".dl.dd[-1].span", matchesPattern("in .+|in under a minute"));
    }

    @Test
    void theActivityViewListsTheRunsOfTheHistoryOnOnePage() {
        Authenticated.asUser()
                .when()
                .get("/activity")
                .then()
                .statusCode(200)
                .body(
                        "html.body.main.article.h2.collect { it.text() }",
                        contains("2026-11-16 07:00:00", "2026-11-16 06:00:00"))
                .body("html.body.main.nav.size()", is(0));
    }

    @Test
    void theLastSuccessfulRunGaugeIsTheOneOfTheHistory() {
        given().when()
                .get("/q/metrics")
                .then()
                .statusCode(200)
                .body(containsString("seedkeeper_last_successful_run_timestamp_seconds "
                        + (double) SUCCEEDED.plusSeconds(4).toEpochSecond()));
    }
}
