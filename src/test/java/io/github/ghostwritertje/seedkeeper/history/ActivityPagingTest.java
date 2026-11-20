package io.github.ghostwritertje.seedkeeper.history;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Status;
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
 * The Activity view pages its runs, 20 to a page: seedkeeper starts here on a data dir whose history has 25 runs,
 * one an hour from 2026-11-01 00:00 UTC.
 */
@QuarkusTest
@TestProfile(ActivityPagingTest.TwentyFiveRuns.class)
class ActivityPagingTest {

    private static final ZonedDateTime FIRST = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final String PAGES = "html.body.main.nav.find { it.@'aria-label' == 'Pages' }";

    public static class TwentyFiveRuns implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config =
                    TestSecrets.with("seedkeeper.transmission.url", "http://127.0.0.1:9/transmission/rpc");
            config.put("seedkeeper.library-roots", "/library");
            config.put("seedkeeper.time-zone", "UTC");
            config.put("seedkeeper.data-dir", dataDirWithRuns(25).toString());
            return config;
        }
    }

    static Path dataDirWithRuns(int runs) {
        try {
            Path dataDir = Files.createTempDirectory("seedkeeper-paging");
            SQLiteDataSource database = new SQLiteDataSource();
            database.setUrl("jdbc:sqlite:" + dataDir.resolve("seedkeeper.db"));
            Flyway.configure()
                    .dataSource(database)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            History history = new History(database);
            for (int run = 0; run < runs; run++) {
                ZonedDateTime started = FIRST.plusHours(run);
                history.record(new RunOutcome(
                        started, started.plusSeconds(1), Status.SUCCEEDED, Optional.empty(), List.of(), List.of()));
            }
            return dataDir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theFirstPageShowsTheNewestTwentyRunsAndLeadsToOlderRuns() {
        Authenticated.asUser()
                .when()
                .get("/activity")
                .then()
                .statusCode(200)
                .body("html.body.main.article.size()", is(20))
                .body("html.body.main.article[0].h2", is("2026-11-02 00:00:00"))
                .body("html.body.main.article[19].h2", is("2026-11-01 05:00:00"))
                .body(PAGES + ".a.size()", is(1))
                .body(PAGES + ".a", is("Older runs"))
                .body(PAGES + ".a.@href", is("/activity?page=2"))
                .body(PAGES + ".p", is("Page 1 of 2"));
    }

    @Test
    void aRunThatRemovedNothingIsOneLine() {
        Authenticated.asUser()
                .when()
                .get("/activity")
                .then()
                .statusCode(200)
                .body("html.body.main.article[0].@data-quiet", notNullValue())
                .body("html.body.main.article[0].children().size()", is(2))
                .body("html.body.main.article[0].h2", is("2026-11-02 00:00:00"))
                .body("html.body.main.article[0].p.text()", matchesPattern("Succeeded\\s*·\\s*nothing to remove\\s*"))
                .body("html.body.main.article[0].p.mark", is("Succeeded"))
                .body("html.body.main.article[0].p.mark.@data-tone", is("ok"))
                .body("html.body.main.article[0].dl.size()", is(0));
    }

    @Test
    void theSecondPageShowsTheOldestRunsAndLeadsToNewerRuns() {
        Authenticated.asUser()
                .queryParam("page", "2")
                .when()
                .get("/activity")
                .then()
                .statusCode(200)
                .body(
                        "html.body.main.article.h2.collect { it.text() }",
                        contains(
                                "2026-11-01 04:00:00",
                                "2026-11-01 03:00:00",
                                "2026-11-01 02:00:00",
                                "2026-11-01 01:00:00",
                                "2026-11-01 00:00:00"))
                .body(PAGES + ".a.size()", is(1))
                .body(PAGES + ".a", is("Newer runs"))
                .body(PAGES + ".a.@href", is("/activity?page=1"))
                .body(PAGES + ".p", is("Page 2 of 2"));
    }

    @Test
    void aPageOutOfRangeOrNoNumberIsClamped() {
        Authenticated.asUser()
                .queryParam("page", "0")
                .when()
                .get("/activity")
                .then()
                .statusCode(200)
                .body(PAGES + ".p", is("Page 1 of 2"));
        Authenticated.asUser()
                .queryParam("page", "99")
                .when()
                .get("/activity")
                .then()
                .statusCode(200)
                .body("html.body.main.article.size()", is(5))
                .body(PAGES + ".p", is("Page 2 of 2"));
        Authenticated.asUser()
                .queryParam("page", "abc")
                .when()
                .get("/activity")
                .then()
                .statusCode(200)
                .body(PAGES + ".p", is("Page 1 of 2"));
    }

    @Test
    void theApiStillListsEveryRun() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/runs")
                .then()
                .statusCode(200)
                .body("$", hasSize(25))
                .body("findAll { it.status != 'succeeded' }", empty());
    }
}
