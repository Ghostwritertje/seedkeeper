package io.github.ghostwritertje.seedkeeper.system;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmission;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Run now, end to end: the button of the System view, the fake Transmission and real files. The trackers are those of
 * {@link FakeTransmissionResource}: tracker-a asks for 30 days of seed time.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class RunNowTest {

    private static final long DAY = 86_400;
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String RUNS = "html.body.main.section.find { it.@id == 'runs' }";

    private Path due;
    private Path dueLink;
    private Path linked;
    private Path inLibrary;

    @BeforeEach
    void aDueGroupAndAGroupWithALibraryLink() throws IOException {
        Path downloads = FakeTransmissionResource.downloads();
        due = file(downloads.resolve("RunNow.Due/movie.mkv"));
        dueLink = downloads.resolve("RunNow.cross-seed/RunNow.Due/movie.mkv");
        Files.createDirectories(dueLink.getParent());
        if (!Files.isSymbolicLink(dueLink)) {
            Files.createSymbolicLink(dueLink, due);
        }
        linked = file(downloads.resolve("RunNow.Linked/movie.mkv"));
        inLibrary = FakeTransmissionResource.library().resolve("RunNow Linked.mkv");
        if (Files.notExists(inLibrary)) {
            Files.createLink(inLibrary, linked);
        }

        FakeTransmissionResource.fake().reset();
        FakeTransmissionResource.fake().torrents("[" + String.join(",",
                torrent("due1", "RunNow.Due", 31 * DAY, downloads, "RunNow.Due/movie.mkv"),
                torrent("linked", "RunNow.Linked", 400 * DAY, downloads, "RunNow.Linked/movie.mkv"),
                torrent("due2", "RunNow.Due.cross-seed", 30 * DAY, downloads.resolve("RunNow.cross-seed"),
                        "RunNow.Due/movie.mkv")) + "]");
    }

    @Test
    void runNowRemovesADueGroupDeletesItsFilesAndShowsTheOutcome() {
        Cookie session = Authenticated.login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Response page = given().cookie(session).when().get("/system");
        page.then().statusCode(200)
                .body(RUNS + ".form.@action", is("/system/run"))
                .body(RUNS + ".form.@method", is("post"))
                .body(RUNS + ".form.button", is("Run now"));

        Response ran = given().redirects().follow(false)
                .cookie(session)
                .cookie(CSRF_COOKIE, page.cookie(CSRF_COOKIE))
                .formParam("csrf-token", csrfToken(page))
                .when().post("/system/run");

        ran.then().statusCode(303).header("Location", endsWith("/system"));
        assertEquals(List.of("due1", "due2"), removalsAsked());
        assertTrue(FakeTransmissionResource.fake().requests().stream()
                .filter(request -> request.body().contains("\"method\":\"torrent_remove\""))
                .allMatch(request -> request.body().contains("\"delete_local_data\":false")),
                "Transmission was asked to delete data");
        assertGone(due, dueLink, due.getParent(), dueLink.getParent());
        assertTrue(Files.exists(linked), "the file of a group with a library link was deleted");
        assertTrue(Files.exists(inLibrary), "a file in the library was deleted");

        given().cookie(session).when().get("/system").then().statusCode(200)
                .body(RUNS + ".dl.dt.collect { it.text() }", contains("Status", "Last run", "Started", "Ended",
                        "Removed", "Files deleted", "Space freed", "Next run"))
                .body(RUNS + ".dl.dd[0]", is("Idle"))
                .body(RUNS + ".dl.dd[1]", is("Succeeded"))
                .body(RUNS + ".dl.dd[2]", matchesPattern("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d"))
                .body(RUNS + ".dl.dd[4]", is("2 torrents in 1 group"))
                .body(RUNS + ".dl.dd[5]", is("2"))
                .body(RUNS + ".dl.dd[6]", is("4 B"));
        Authenticated.withApiKey().when().get("/api/v1/system").then().statusCode(200)
                .body("running", is(false))
                .body("lastRun.status", is("succeeded"))
                .body("lastRun.error", nullValue())
                .body("lastRun.started", matchesPattern("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+05:30"))
                .body("lastRun.groupsRemoved", is(1))
                .body("lastRun.torrentsRemoved", is(2))
                .body("lastRun.filesDeleted", is(2))
                .body("lastRun.spaceFreedBytes", is(4))
                .body("lastRun.removalErrors", empty());
    }

    @Test
    void aRunEndsFailedWithTheReasonWhenTransmissionIsTooOld() {
        FakeTransmissionResource.fake().version("4.0.6 (38c164933e)");

        runNow().then().statusCode(303);

        assertEquals(List.of(), removalsAsked());
        assertTrue(Files.exists(due));
        Authenticated.asUser().when().get("/system").then()
                .body(RUNS + ".dl.dd[1]", is("Failed"))
                .body(RUNS + ".p.find { it.@role == 'alert' }", is("Transmission 4.0.6 (38c164933e) is older than 4.1,"
                        + " the oldest version seedkeeper works with"));
        Authenticated.withApiKey().when().get("/api/v1/system").then()
                .body("lastRun.status", is("failed"))
                .body("lastRun.error", containsString("is older than 4.1"))
                .body("lastRun.torrentsRemoved", is(0));
    }

    @Test
    void aRemovalThatFailsDeletesNoFilesAndIsShown() {
        FakeTransmissionResource.fake().refuseRemovalOf("due2");

        runNow().then().statusCode(303);

        assertTrue(Files.exists(due) && Files.isSymbolicLink(dueLink), "a file of the group that failed was deleted");
        Authenticated.asUser().when().get("/system").then()
                .body(RUNS + ".dl.dd[1]", is("Succeeded"))
                .body(RUNS + ".dl.dd[4]", is("1 torrent in 0 groups"))
                .body(RUNS + ".dl.dd[5]", is("0"))
                .body(RUNS + ".ul.li[0]", matchesPattern("RunNow\\.Due, RunNow\\.Due\\.cross-seed: Transmission at .+"
                        + " refused torrent_remove: torrent is busy\\. No files were deleted\\."));
    }

    @Test
    void whileARunIsRunningRunNowAnswersAlreadyRunning() throws Exception {
        RequestSpecification firstForm = runNowForm();
        RequestSpecification secondForm = runNowForm();
        // The first run stands still at its first question to Transmission until the hold is released.
        FakeTransmission.Hold hold = FakeTransmissionResource.fake().holdNextSessionGet();
        CompletableFuture<Response> first = CompletableFuture.supplyAsync(() -> firstForm.post("/system/run"));
        assertTrue(hold.reached(10, TimeUnit.SECONDS), "the first run never asked Transmission for its session");
        try {
            secondForm.post("/system/run").then().statusCode(409)
                    .body(RUNS + ".p.find { it.@role == 'alert' }", is("A run is already running."))
                    .body(RUNS + ".dl.dd[0]", is("Running now"));
            assertEquals(List.of(), removalsAsked());
        } finally {
            hold.release();
        }

        first.get(30, TimeUnit.SECONDS).then().statusCode(303);
        assertEquals(List.of("due1", "due2"), removalsAsked());
    }

    @Test
    void runNowWithoutItsCsrfTokenIsRejectedAndNothingRuns() {
        Cookie session = Authenticated.login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Response page = given().cookie(session).when().get("/system");

        given().redirects().follow(false)
                .cookie(session)
                .cookie(CSRF_COOKIE, page.cookie(CSRF_COOKIE))
                .contentType("application/x-www-form-urlencoded")
                .when().post("/system/run")
                .then().statusCode(400);

        assertEquals(List.of(), removalsAsked());
        assertTrue(Files.exists(due));
    }

    @Test
    void runNowIsNotInTheApi() {
        Authenticated.withApiKey().redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .when().post("/system/run")
                .then().statusCode(anyOf(is(302), is(400), is(401)));
        Authenticated.withApiKey()
                .contentType("application/x-www-form-urlencoded")
                .when().post("/api/v1/system/run")
                .then().statusCode(not(anyOf(is(200), is(204), is(303))));

        assertEquals(List.of(), removalsAsked());
        assertTrue(Files.exists(due));
    }

    /** Presses Run now as a logged-in user, with the CSRF token of the form. */
    private Response runNow() {
        return runNowForm().post("/system/run");
    }

    /** The Run now form of a logged-in user, filled in with its CSRF token and ready to post. */
    private RequestSpecification runNowForm() {
        Cookie session = Authenticated.login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Response page = given().cookie(session).when().get("/system");
        return given().redirects().follow(false)
                .cookie(session)
                .cookie(CSRF_COOKIE, page.cookie(CSRF_COOKIE))
                .formParam("csrf-token", csrfToken(page));
    }

    private static String csrfToken(Response page) {
        String token = page.htmlPath().getString(RUNS + ".form.input.find { it.@name == 'csrf-token' }.@value");
        assertFalse(token == null || token.isBlank(), "the Run now form has no CSRF token");
        return token;
    }

    /** The hashes Transmission was asked to remove, in order. */
    private static List<String> removalsAsked() {
        return FakeTransmissionResource.fake().requests().stream()
                .map(FakeTransmission.Request::body)
                .filter(body -> body.contains("\"method\":\"torrent_remove\""))
                .map(body -> body.replaceAll(".*\"ids\":\\[\"([^\"]+)\"].*", "$1"))
                .toList();
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

    private static void assertGone(Path... paths) {
        for (Path path : paths) {
            assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS), path + " is still there");
        }
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }
}
