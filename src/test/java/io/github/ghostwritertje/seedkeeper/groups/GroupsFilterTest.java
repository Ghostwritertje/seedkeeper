package io.github.ghostwritertje.seedkeeper.groups;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Missing data, local errors, verifying and secondary reasons in the Groups view and {@code GET /api/v1/groups}, and
 * their filters by verdict and reason, end to end.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class GroupsFilterTest {

    private static final long DAY = 86_400;
    private static final int SEEDING = 6;
    private static final int CHECKING = 2;
    private static final int DOWNLOADING = 4;
    private static final int NO_ERROR = 0;
    private static final int LOCAL_ERROR = 3;

    @BeforeEach
    void torrents() throws IOException {
        Path downloads = FakeTransmissionResource.downloads();
        Path library = FakeTransmissionResource.library();
        file(downloads.resolve("Broken.Movie/movie.mkv"));
        file(downloads.resolve("Checking.Movie/movie.mkv"));
        Path kept = file(downloads.resolve("Kept.Movie/movie.mkv"));
        file(downloads.resolve("Done.Movie/movie.mkv"));
        file(downloads.resolve("Half.Gone/e01.mkv"));
        if (Files.notExists(library.resolve("movies/Kept Movie.mkv"))) {
            Files.createDirectories(library.resolve("movies"));
            Files.createLink(library.resolve("movies/Kept Movie.mkv"), kept);
        }

        FakeTransmissionResource.fake().reset();
        FakeTransmissionResource.fake().torrents("[" + String.join(",",
                // Finished, but one of its two files is no longer on disk.
                torrent("aaaa", "Half.Gone", SEEDING, 1, NO_ERROR, 400 * DAY, "Half.Gone/e01.mkv",
                        "Half.Gone/e02.mkv"),
                torrent("bbbb", "Broken.Movie", SEEDING, 1, LOCAL_ERROR, DAY, "Broken.Movie/movie.mkv"),
                torrent("cccc", "Checking.Movie", CHECKING, 1, NO_ERROR, 400 * DAY, "Checking.Movie/movie.mkv"),
                torrent("dddd", "Kept.Movie", SEEDING, 1, NO_ERROR, 0, "Kept.Movie/movie.mkv"),
                torrent("eeee", "Done.Movie", SEEDING, 1, NO_ERROR, 400 * DAY, "Done.Movie/movie.mkv"),
                // Not finished: its file isn't on disk yet, which is not missing data.
                torrent("ffff", "Not.Yet", DOWNLOADING, 0.5, NO_ERROR, 0, "Not.Yet/movie.mkv")) + "]");
    }

    @Test
    void apiGivesMissingDataLocalErrorVerifyingAndTheSecondaryReasons() {
        Authenticated.withApiKey()
                .when().get("/api/v1/groups")
                .then()
                .statusCode(200)
                .body("members.flatten().hash", contains("aaaa", "bbbb", "cccc", "dddd", "eeee", "ffff"))
                .body("verdict", contains("left-alone", "left-alone", "keep", "keep", "remove", "keep"))
                .body("reason", contains("missing-data", "local-error", "verifying", "library-link", "all-met",
                        "not-finished"))
                .body("secondaryReasons", is(List.of(List.of(), List.of("minimum-not-reached"), List.of(),
                        List.of("minimum-not-reached"), List.of(), List.of("minimum-not-reached"))))
                .body("missingFiles", contains(1, 0, 0, 0, 0, 0))
                .body("files", contains(2, 1, 1, 1, 1, 1));
    }

    @Test
    void apiFiltersByVerdict() {
        Authenticated.withApiKey()
                .when().get("/api/v1/groups?verdict=left-alone")
                .then()
                .statusCode(200)
                .body("members.flatten().hash", contains("aaaa", "bbbb"));
    }

    @Test
    void apiFiltersByReason() {
        Authenticated.withApiKey()
                .when().get("/api/v1/groups?reason=verifying")
                .then()
                .statusCode(200)
                .body("members.flatten().hash", contains("cccc"));
    }

    @Test
    void apiFiltersByTheReasonThatDecidedNotBySecondaryReasons() {
        Authenticated.withApiKey()
                .when().get("/api/v1/groups?reason=minimum-not-reached")
                .then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void apiFiltersByVerdictAndReasonTogether() {
        Authenticated.withApiKey()
                .when().get("/api/v1/groups?verdict=keep&reason=library-link")
                .then()
                .statusCode(200)
                .body("members.flatten().hash", contains("dddd"));
        Authenticated.withApiKey()
                .when().get("/api/v1/groups?verdict=remove&reason=library-link")
                .then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void apiTreatsAnEmptyFilterAsNoFilter() {
        Authenticated.withApiKey()
                .when().get("/api/v1/groups?verdict=&reason=")
                .then()
                .statusCode(200)
                .body("size()", is(6));
    }

    @Test
    void apiRejectsAnUnknownVerdictOrReason() {
        Authenticated.withApiKey()
                .when().get("/api/v1/groups?verdict=delete")
                .then()
                .statusCode(400)
                .contentType("application/json")
                .body("reason", is("Unknown verdict 'delete': use remove, left-alone or keep"));
        Authenticated.withApiKey()
                .when().get("/api/v1/groups?reason=libary-link")
                .then()
                .statusCode(400)
                .body("reason", is("Unknown reason 'libary-link': use unregistered, unmanaged, missing-data, local-error,"
                        + " verifying, not-finished, library-link, minimum-not-reached or all-met"));
    }

    @Test
    void pageShowsTheCountOfMissingFilesAndTheSecondaryReasons() {
        Authenticated.asUser()
                .when().get("/groups")
                .then()
                .statusCode(200)
                .body("html.body.main.table.thead.tr.th", contains("Name", "Tracker", "Seed time / minimum",
                        "Status", "Tracker message", "Library links", "Verdict", "Reason", "Secondary reasons", "Forecast"))
                .body(cells(0), contains("Half.Gone", "tracker-a", "400d / 30d", "Seeding", "", "0 of 2 files",
                        "Left alone", "Missing data (1 of 2 files)", "", ""))
                .body(cells(1), contains("Broken.Movie", "tracker-a", "1d / 30d", "Seeding", "", "0 of 1 files",
                        "Left alone", "Local error", "Minimum not reached", ""))
                .body(cells(2), contains("Checking.Movie", "tracker-a", "400d / 30d", "Verifying", "", "0 of 1 files",
                        "Keep", "Verifying", "", ""))
                .body(cells(5), contains("Not.Yet", "tracker-a", "0m / 30d", "Downloading", "", "0 of 1 files",
                        "Keep", "Not finished", "Minimum not reached", ""));
    }

    @Test
    void pageListsSeveralSecondaryReasonsInTheOrderOfPrecedence() throws IOException {
        FakeTransmissionResource.fake().torrents("[" + torrent("dddd", "Kept.Movie", CHECKING, 1, LOCAL_ERROR, 0,
                "Kept.Movie/movie.mkv") + "]");

        Authenticated.asUser()
                .when().get("/groups")
                .then()
                .body(cells(0), contains("Kept.Movie", "tracker-a", "0m / 30d", "Verifying", "", "1 of 1 files",
                        "Left alone", "Local error", "Verifying, Library link (1 of 1 files), Minimum not reached",
                        ""));
    }

    @Test
    void pageFiltersByVerdictAndReason() {
        Authenticated.asUser()
                .when().get("/groups?verdict=keep")
                .then()
                .statusCode(200)
                .body("html.body.main.table.tbody.tr.collect { it.td[0].text() }",
                        contains("Checking.Movie", "Kept.Movie", "Not.Yet"))
                .body(selected("verdict"), is("keep"))
                .body(selected("reason"), is(""));
        Authenticated.asUser()
                .when().get("/groups?verdict=left-alone&reason=missing-data")
                .then()
                .body("html.body.main.table.tbody.tr.size()", is(1))
                .body("html.body.main.table.tbody.tr[0].td[0]", is("Half.Gone"))
                .body(selected("verdict"), is("left-alone"))
                .body(selected("reason"), is("missing-data"));
    }

    @Test
    void pageOffersEveryVerdictAndReasonAsAFilter() {
        Authenticated.asUser()
                .when().get("/groups")
                .then()
                .body("html.body.main.form.@method", is("get"))
                .body("html.body.main.form.@action", is("/groups"))
                .body(options("verdict", "@value"), contains("", "remove", "left-alone", "keep"))
                .body(options("verdict", "text()"), contains("Any verdict", "Remove", "Left alone", "Keep"))
                .body(options("reason", "@value"), contains("", "unregistered", "unmanaged", "missing-data", "local-error",
                        "verifying", "not-finished", "library-link", "minimum-not-reached", "all-met"))
                .body(options("reason", "text()"), contains("Any reason", "Unregistered", "Unmanaged", "Missing data", "Local error",
                        "Verifying", "Not finished", "Library link", "Minimum not reached", "All met"))
                .body(selected("verdict"), is(""))
                .body(selected("reason"), is(""));
    }

    @Test
    void pageSaysSoWhenNoGroupMatchesTheFilter() {
        Authenticated.asUser()
                .when().get("/groups?verdict=remove&reason=library-link")
                .then()
                .statusCode(200)
                .body("html.body.main.table.size()", is(0))
                .body("html.body.main.p.size()", is(1))
                .body("html.body.main.p", is("No group has this verdict and reason."))
                .body(selected("reason"), is("library-link"));
    }

    @Test
    void pageGivesTheReasonWhenTheFilterIsUnknown() {
        Authenticated.asUser()
                .when().get("/groups?verdict=delete")
                .then()
                .statusCode(200)
                .body("html.body.main.p.find { it.@role == 'alert' }",
                        is("Unknown verdict 'delete': use remove, left-alone or keep"))
                .body("html.body.main.table.size()", is(0))
                .body(selected("verdict"), is(""));
    }

    /** The text of every cell of a row of the table. */
    private static String cells(int row) {
        return "html.body.main.table.tbody.tr[" + row + "].td.collect { it.text() }";
    }

    /** The value of the selected option of a filter. */
    private static String selected(String select) {
        return "html.body.main.form.'**'.find { it.name() == 'select' && it.@name == '" + select
                + "' }.option.find { it.@selected == 'selected' }.@value";
    }

    private static String options(String select, String what) {
        return "html.body.main.form.'**'.find { it.name() == 'select' && it.@name == '" + select
                + "' }.option.collect { it." + what + ".toString() }";
    }

    /** One private torrent of tracker-a in Transmission's field names; every file is wanted. */
    private static String torrent(String hash, String name, int status, double percentDone, int error,
            long secondsSeeding, String... files) {
        StringBuilder fileList = new StringBuilder();
        StringBuilder fileStats = new StringBuilder();
        for (String file : files) {
            fileList.append(fileList.isEmpty() ? "" : ",")
                    .append("{\"name\":\"").append(file).append("\",\"length\":4}");
            fileStats.append(fileStats.isEmpty() ? "" : ",").append("{\"wanted\":true}");
        }
        return """
                {"hash_string": "%s", "name": "%s", "status": %d, "percent_done": %s, "error": %d,
                 "error_string": "%s", "seconds_seeding": %d, "is_private": true, "download_dir": "%s",
                 "trackers": [{"announce": "https://tracker-a.example/key/announce"}], "files": [%s],
                 "file_stats": [%s]}
                """.formatted(hash, name, status, percentDone, error, error == 0 ? "" : "No data found!",
                secondsSeeding, FakeTransmissionResource.downloads(), fileList, fileStats);
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }
}
