package io.github.ghostwritertje.seedkeeper.history;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.ScrollRegions;
import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The run history, end to end: Run now against the fake Transmission and real files, then the Activity view,
 * {@code GET /api/v1/runs}, {@code GET /api/v1/removals} and {@code reasonSince} of {@code GET /api/v1/groups}. The
 * history is that of the whole application under test, so the runs of other tests are in it too: these tests look at
 * the newest run, which comes first.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class ActivityTest {

    private static final long DAY = 86_400;
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String MOMENT = "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+05:30";
    private static final String NEWEST_RUN = "html.body.main.article[0]";

    @BeforeEach
    void aDueGroupAndAGroupStillSeeding() throws IOException {
        Path downloads = FakeTransmissionResource.downloads();
        Path due = file(downloads.resolve("Activity.Due/movie.mkv"));
        Path dueLink = downloads.resolve("Activity.cross-seed/Activity.Due/movie.mkv");
        Files.createDirectories(dueLink.getParent());
        if (!Files.isSymbolicLink(dueLink)) {
            Files.createSymbolicLink(dueLink, due);
        }
        file(downloads.resolve("Activity.Fresh/movie.mkv"));

        FakeTransmissionResource.fake().reset();
        FakeTransmissionResource.fake()
                .torrents("["
                        + String.join(
                                ",",
                                torrent("act1", "Activity.Due", 31 * DAY, downloads, "Activity.Due/movie.mkv"),
                                torrent(
                                        "act2",
                                        "Activity.Due.cross-seed",
                                        30 * DAY,
                                        downloads.resolve("Activity.cross-seed"),
                                        "Activity.Due/movie.mkv"),
                                torrent("act3", "Activity.Fresh", 2 * DAY, downloads, "Activity.Fresh/movie.mkv"))
                        + "]");
    }

    @Test
    void apiListsARunAndWhatItRemoved() {
        runNow();

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/runs")
                .then()
                .statusCode(200)
                .body("[0].id", notNullValue())
                .body("[0].started", matchesPattern(MOMENT))
                .body("[0].ended", matchesPattern(MOMENT))
                .body("[0].status", is("succeeded"))
                .body("[0].error", nullValue())
                .body("[0].groupsRemoved", is(1))
                .body("[0].torrentsRemoved", is(2))
                .body("[0].filesDeleted", is(2))
                .body("[0].spaceFreedBytes", is(4));
        int run = Authenticated.withApiKey()
                .when()
                .get("/api/v1/runs")
                .then()
                .extract()
                .path("[0].id");
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/removals")
                .then()
                .statusCode(200)
                .body("[0].runId", is(run))
                .body("[0].started", matchesPattern(MOMENT))
                .body("[0].reason", is("all-met"))
                .body("[0].error", nullValue())
                .body("[0].spaceFreedBytes", is(4))
                .body("[0].filesDeleted", is(2))
                .body("[0].pathsRefused", is(0))
                .body("[0].members.hash", contains("act1", "act2"))
                .body("[0].members.name", contains("Activity.Due", "Activity.Due.cross-seed"))
                .body("[0].members.tracker", contains("tracker-a", "tracker-a"))
                .body("[0].members.trackerMessage", contains(nullValue(), nullValue()))
                .body("[0].members.unfinished", contains(false, false))
                .body("[0].members.removed", contains(true, true));
    }

    @Test
    void apiListsAFailedRunWithItsError() {
        FakeTransmissionResource.fake().version("4.0.6 (38c164933e)");

        runNow();

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/runs")
                .then()
                .statusCode(200)
                .body("[0].status", is("failed"))
                .body("[0].error", containsString("is older than 4.1"))
                .body("[0].torrentsRemoved", is(0));
    }

    @Test
    void apiListsARemovalThatFailedWithItsError() {
        FakeTransmissionResource.fake().refuseRemovalOf("act2");

        runNow();

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/runs")
                .then()
                .statusCode(200)
                .body("[0].status", is("succeeded"))
                .body("[0].groupsRemoved", is(0))
                .body("[0].torrentsRemoved", is(1));
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/removals")
                .then()
                .statusCode(200)
                .body("[0].error", containsString("refused torrent_remove: torrent is busy"))
                .body("[0].filesDeleted", is(0))
                .body("[0].members.removed", contains(true, false));
    }

    @Test
    void sinceLeavesOutWhatIsOlder() {
        runNow();

        Authenticated.withApiKey()
                .queryParam("since", "2000-01-01")
                .when()
                .get("/api/v1/runs")
                .then()
                .statusCode(200)
                .body("[0].status", is("succeeded"));
        Authenticated.withApiKey()
                .queryParam("since", "2999-01-01T00:00:00Z")
                .when()
                .get("/api/v1/runs")
                .then()
                .statusCode(200)
                .body("$", empty());
        Authenticated.withApiKey()
                .queryParam("since", "2999-01-01T00:00:00+01:00")
                .when()
                .get("/api/v1/removals")
                .then()
                .statusCode(200)
                .body("$", empty());
        Authenticated.withApiKey()
                .queryParam("since", "yesterday")
                .when()
                .get("/api/v1/runs")
                .then()
                .statusCode(400)
                .body("reason", containsString("yesterday"));
        Authenticated.withApiKey()
                .queryParam("since", "yesterday")
                .when()
                .get("/api/v1/removals")
                .then()
                .statusCode(400)
                .body("reason", containsString("yesterday"));
    }

    @Test
    void theApiNeedsTheKey() {
        given().when().get("/api/v1/runs").then().statusCode(401);
        given().when().get("/api/v1/removals").then().statusCode(401);
    }

    @Test
    void groupsHaveTheirReasonSinceTheRunThatFirstSawIt() throws IOException {
        Path downloads = FakeTransmissionResource.downloads();
        file(downloads.resolve("Activity.Since/movie.mkv"));
        FakeTransmissionResource.fake()
                .torrents("[" + torrent("since1", "Activity.Since", 2 * DAY, downloads, "Activity.Since/movie.mkv")
                        + "]");
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .statusCode(200)
                .body("[0].reason", is("minimum-not-reached"))
                .body("[0].reasonSince", nullValue());

        runNow();

        String started = Authenticated.withApiKey()
                .when()
                .get("/api/v1/runs")
                .then()
                .extract()
                .path("[0].started");
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .statusCode(200)
                .body("[0].reasonSince", is(started));

        runNow();

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .statusCode(200)
                .body("[0].reasonSince", is(started));
    }

    @Test
    void pageListsARunAndWhatItRemoved() {
        runNow();

        Authenticated.asUser()
                .when()
                .get("/activity")
                .then()
                .statusCode(200)
                .body("html.head.title", is("Activity · seedkeeper"))
                .body(NEWEST_RUN + ".h2.time", matchesPattern("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d"))
                .body(NEWEST_RUN + ".h2.span", is("just now"))
                .body(
                        NEWEST_RUN + ".dl.dt.collect { it.text() }",
                        contains("Status", "Took", "Removed", "Files deleted", "Space freed"))
                .body(NEWEST_RUN + ".dl.dd[0]", is("Succeeded"))
                .body(NEWEST_RUN + ".dl.dd[2]", is("2 torrents in 1 group"))
                .body(NEWEST_RUN + ".dl.dd[3]", is("2"))
                .body(NEWEST_RUN + ".dl.dd[4]", is("4 B"))
                .body(
                        ScrollRegions.tableParents(NEWEST_RUN),
                        matchesPattern("div\\|region\\|0\\|Removals of the run of \\d{4}-\\d\\d-\\d\\d"
                                + " \\d\\d:\\d\\d:\\d\\d"))
                .body(
                        NEWEST_RUN + ".div.table.thead.tr.th.collect { it.text() }",
                        contains("Removed", "Reason", "Files deleted", "Space freed", "Error"))
                .body(
                        NEWEST_RUN + ".div.table.tbody.tr[0].td[0].ul.li.collect { it.text() }",
                        contains("Activity.Due (tracker-a)", "Activity.Due.cross-seed (tracker-a)"))
                .body(NEWEST_RUN + ".div.table.tbody.tr[0].td[1]", is("All met"))
                .body(NEWEST_RUN + ".div.table.tbody.tr[0].td[2].span", is("2"))
                .body(NEWEST_RUN + ".div.table.tbody.tr[0].td[2].small.size()", is(0))
                .body(NEWEST_RUN + ".div.table.tbody.tr[0].td[3].span", is("4 B"))
                .body(NEWEST_RUN + ".div.table.tbody.tr[0].td[3].small.size()", is(0))
                .body(NEWEST_RUN + ".div.table.tbody.tr[0].td[4]", is(""));
    }

    @Test
    void pageShowsTheErrorOfARemovalThatFailedAndOfAFailedRun() {
        FakeTransmissionResource.fake().refuseRemovalOf("act2");
        runNow();

        Authenticated.asUser()
                .when()
                .get("/activity")
                .then()
                .statusCode(200)
                .body(NEWEST_RUN + ".dl.dd[2]", is("1 torrent in 0 groups"))
                .body(
                        NEWEST_RUN + ".div.table.tbody.tr[0].td[0].ul.li.collect { it.text() }",
                        contains("Activity.Due (tracker-a)", "Activity.Due.cross-seed (tracker-a, not removed)"))
                .body(
                        NEWEST_RUN + ".div.table.tbody.tr[0].td[4]",
                        matchesPattern("Transmission at .+ refused torrent_remove: torrent is busy\\. No files were"
                                + " deleted\\."));

        FakeTransmissionResource.fake().version("4.0.6 (38c164933e)");
        runNow();

        Authenticated.asUser()
                .when()
                .get("/activity")
                .then()
                .statusCode(200)
                .body(NEWEST_RUN + ".dl.dd[0]", is("Failed"))
                .body(NEWEST_RUN + ".p.find { it.@role == 'alert' }", containsString("is older than 4.1"));
    }

    @Test
    void pageExplainsTheNumbersAndARemovalThatDeletedNoFiles() {
        Path downloads = FakeTransmissionResource.downloads();
        // An unregistered cross-seed of a torrent still seeding leaves on its own; the file stays for the other one.
        FakeTransmissionResource.fake()
                .torrents("["
                        + torrent("act3", "Activity.Fresh", 2 * DAY, downloads, "Activity.Fresh/movie.mkv")
                        + ","
                        + torrent(
                                        "gone1",
                                        "Activity.Fresh.unregistered",
                                        2 * DAY,
                                        downloads,
                                        "Activity.Fresh/movie.mkv")
                                .replace(
                                        "\"error\": 0, \"error_string\": \"\"",
                                        "\"error\": 2, \"error_string\": \"Unregistered torrent\"")
                        + "]");
        runNow();

        Authenticated.asUser()
                .when()
                .get("/activity")
                .then()
                .statusCode(200)
                .body(
                        "html.body.main.p[0]",
                        allOf(
                                containsString("Removed counts"),
                                containsString("Files deleted counts"),
                                containsString("Space freed counts")))
                .body(
                        NEWEST_RUN + ".div.table.tbody.tr[0].td[0].ul.li",
                        is("Activity.Fresh.unregistered (tracker-a, tracker said: Unregistered torrent)"))
                .body(NEWEST_RUN + ".div.table.tbody.tr[0].td[2].span", is("0"))
                .body(NEWEST_RUN + ".div.table.tbody.tr[0].td[3].span", is("0 B"))
                .body(NEWEST_RUN + ".div.table.tbody.tr[0].td[3].small.size()", is(0))
                .body(
                        NEWEST_RUN + ".div.table.tbody.tr[0].td[2].small",
                        is("Data kept: every file is still used by a torrent that stays (a cross-seed), or was no"
                                + " longer on disk."))
                .body(NEWEST_RUN + ".div.table.tbody.tr[0].td[4]", is(""));
    }

    @Test
    void theActivityAndSystemViewsAgreeOnWhenTheLastRunStartedAndHowLongItTook() {
        runNow();

        String system = "html.body.main.section.find { it.@id == 'runs' }.dl";
        XmlPath systemPage = Authenticated.asUser().when().get("/system").htmlPath();
        XmlPath activityPage = Authenticated.asUser().when().get("/activity").htmlPath();
        assertEquals(
                systemPage.getString(system + ".dd[2].time.@datetime"),
                activityPage.getString(NEWEST_RUN + ".h2.time.@datetime"));
        assertEquals(systemPage.getString(system + ".dd[3]"), activityPage.getString(NEWEST_RUN + ".dl.dd[1]"));
    }

    @Test
    void theNavigationLeadsToTheActivityView() {
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .statusCode(200)
                .body("html.body.header.nav.a.collect { it.@href }", hasItem("/activity"));
    }

    /** Presses Run now as a logged-in user. */
    private static void runNow() {
        Cookie session = Authenticated.login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Response page = given().cookie(session).when().get("/system");
        String token = page.htmlPath()
                .getString(
                        "html.body.main.section.find { it.@id == 'runs' }.form.input.find { it.@name == 'csrf-token' }.@value");
        assertFalse(token == null || token.isBlank(), "the Run now form has no CSRF token");
        given().redirects()
                .follow(false)
                .cookie(session)
                .cookie(CSRF_COOKIE, page.cookie(CSRF_COOKIE))
                .formParam("csrf-token", token)
                .when()
                .post("/system/run")
                .then()
                .statusCode(303);
    }

    /** A finished, seeding, private torrent of tracker-a with one wanted file. */
    private static String torrent(String hash, String name, long secondsSeeding, Path downloadDir, String file) {
        return """
                {"hash_string": "%s", "name": "%s", "status": 6, "percent_done": 1, "error": 0, "error_string": "",
                 "seconds_seeding": %d, "is_private": true, "download_dir": "%s",
                 "trackers": [{"announce": "https://tracker-a.example/key/announce"}],
                 "files": [{"name": "%s", "length": 4}], "file_stats": [{"wanted": true}]}
                """.formatted(hash, name, secondsSeeding, downloadDir, file);
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }
}
