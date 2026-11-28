package io.github.ghostwritertje.seedkeeper.groups;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

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
    private static final String TALLY = "html.body.main.nav";
    /** The verdict the filter form keeps when the reason changes: the chips choose it, the form has no control for it. */
    private static final String VERDICT =
            "html.body.main.form.'**'.find { it.name() == 'input' && it.@name == 'verdict' }.@value";

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
        FakeTransmissionResource.fake()
                .torrents("["
                        + String.join(
                                ",",
                                // Finished, but one of its two files is no longer on disk.
                                torrent(
                                        "aaaa",
                                        "Half.Gone",
                                        SEEDING,
                                        1,
                                        NO_ERROR,
                                        400 * DAY,
                                        "Half.Gone/e01.mkv",
                                        "Half.Gone/e02.mkv"),
                                torrent("bbbb", "Broken.Movie", SEEDING, 1, LOCAL_ERROR, DAY, "Broken.Movie/movie.mkv"),
                                torrent(
                                        "cccc",
                                        "Checking.Movie",
                                        CHECKING,
                                        1,
                                        NO_ERROR,
                                        400 * DAY,
                                        "Checking.Movie/movie.mkv"),
                                torrent("dddd", "Kept.Movie", SEEDING, 1, NO_ERROR, 0, "Kept.Movie/movie.mkv"),
                                torrent("eeee", "Done.Movie", SEEDING, 1, NO_ERROR, 400 * DAY, "Done.Movie/movie.mkv"),
                                // Not finished: its file isn't on disk yet, which is not missing data.
                                torrent("ffff", "Not.Yet", DOWNLOADING, 0.5, NO_ERROR, 0, "Not.Yet/movie.mkv"))
                        + "]");
    }

    @Test
    void apiGivesMissingDataLocalErrorVerifyingAndTheSecondaryReasons() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .statusCode(200)
                .body("members.flatten().hash", contains("aaaa", "bbbb", "cccc", "dddd", "eeee", "ffff"))
                .body("verdict", contains("left-alone", "left-alone", "keep", "keep", "remove", "keep"))
                .body(
                        "reason",
                        contains("missing-data", "local-error", "verifying", "library-link", "all-met", "not-finished"))
                .body(
                        "secondaryReasons",
                        is(List.of(
                                List.of(),
                                List.of("minimum-not-reached"),
                                List.of(),
                                List.of("minimum-not-reached"),
                                List.of(),
                                List.of("minimum-not-reached"))))
                .body("missingFiles", contains(1, 0, 0, 0, 0, 0))
                .body("files", contains(2, 1, 1, 1, 1, 1));
    }

    @Test
    void apiFiltersByVerdict() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups?verdict=left-alone")
                .then()
                .statusCode(200)
                .body("members.flatten().hash", contains("aaaa", "bbbb"));
    }

    @Test
    void apiFiltersByReason() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups?reason=verifying")
                .then()
                .statusCode(200)
                .body("members.flatten().hash", contains("cccc"));
    }

    @Test
    void apiFiltersByTheReasonThatDecidedNotBySecondaryReasons() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups?reason=minimum-not-reached")
                .then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void apiFiltersByVerdictAndReasonTogether() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups?verdict=keep&reason=library-link")
                .then()
                .statusCode(200)
                .body("members.flatten().hash", contains("dddd"));
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups?verdict=remove&reason=library-link")
                .then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void apiTreatsAnEmptyFilterAsNoFilter() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups?verdict=&reason=")
                .then()
                .statusCode(200)
                .body("size()", is(6));
    }

    @Test
    void apiRejectsAnUnknownVerdictOrReason() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups?verdict=delete")
                .then()
                .statusCode(400)
                .contentType("application/json")
                .body("reason", is("Unknown verdict 'delete': use remove, left-alone or keep"));
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups?reason=libary-link")
                .then()
                .statusCode(400)
                .body(
                        "reason",
                        is(
                                "Unknown reason 'libary-link': use unregistered, unmanaged, missing-data, local-error,"
                                        + " verifying, not-finished, keep-override, library-link, minimum-not-reached or all-met"));
    }

    @Test
    void pageShowsTheCountOfMissingFilesAndTheSecondaryReasons() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(
                        GroupsTable.HEADINGS,
                        contains("Name", "Minimum seed time", "Verdict", "Reason", "Size", "Keep override"))
                // Remove first, then Left alone, then Keep.
                .body(
                        GroupsTable.NAMES,
                        contains("Done.Movie", "Half.Gone", "Broken.Movie", "Checking.Movie", "Kept.Movie", "Not.Yet"))
                .body(GroupsTable.heading(1), contains("Half.Gone", "tracker-a · Seeding · 0 of 2 files linked"))
                // The file that is gone still counts: the size is what Transmission says the data is.
                .body(GroupsTable.cells(1), contains("Met ✓", "Left alone", "Missing data (1 of 2 files)", "8 B"))
                .body(GroupsTable.underReason(1), is(""))
                .body(GroupsTable.cells(2), contains("3%", "Left alone", "Local error", "4 B"))
                .body(GroupsTable.underReason(2), is("also: Minimum not reached"))
                .body(GroupsTable.heading(3), contains("Checking.Movie", "tracker-a · Verifying · 0 of 1 files linked"))
                .body(GroupsTable.cells(3), contains("Met ✓", "Keep", "Verifying", "4 B"))
                .body(GroupsTable.cells(5), contains("0%", "Keep", "Not finished", "4 B"))
                .body(GroupsTable.underReason(5), is("also: Minimum not reached"));
    }

    @Test
    void pageListsSeveralSecondaryReasonsInTheOrderOfPrecedence() throws IOException {
        FakeTransmissionResource.fake()
                .torrents(
                        "[" + torrent("dddd", "Kept.Movie", CHECKING, 1, LOCAL_ERROR, 0, "Kept.Movie/movie.mkv") + "]");

        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .body(GroupsTable.heading(0), contains("Kept.Movie", "tracker-a · Verifying · 1 of 1 files linked"))
                .body(GroupsTable.cells(0), contains("0%", "Left alone", "Local error", "4 B"))
                .body(GroupsTable.underReason(0), is("also: Verifying, Library link, Minimum not reached"));
    }

    @Test
    void pageFiltersByVerdictAndReason() {
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=keep")
                .then()
                .statusCode(200)
                .body(GroupsTable.NAMES, contains("Checking.Movie", "Kept.Movie", "Not.Yet"))
                .body(VERDICT, is("keep"))
                .body(selected("reason"), is(""));
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=left-alone&reason=missing-data")
                .then()
                .body(GroupsTable.GROUPS + ".size()", is(1))
                .body(GroupsTable.NAMES, is("Half.Gone"))
                .body(VERDICT, is("left-alone"))
                .body(selected("reason"), is("missing-data"));
    }

    @Test
    void theVerdictIsChosenOnlyByTheChips() {
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=keep")
                .then()
                .statusCode(200)
                .body("html.body.'**'.findAll { it.name() == 'select' && it.@name == 'verdict' }.size()", is(0))
                .body("html.body.main.form.'**'.findAll { it.@name == 'verdict' }.size()", is(1))
                .body("html.body.main.form.'**'.find { it.@name == 'verdict' }.@type", is("hidden"));
    }

    @Test
    void theReasonAndTheNameApplyOnChangeAndHaveAButtonOnlyWithoutScripts() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                // Enter in the name field submits the form: htmx takes that too, rather than the browser loading it.
                .body("html.body.main.form.@'hx-trigger'", is("change, submit"))
                .body("html.body.main.form.@'hx-get'", is("/groups"))
                .body("html.body.main.form.button.size()", is(0))
                .body("html.body.main.form.noscript.button", is("Filter"))
                .body("html.body.main.form.'**'.find { it.name() == 'select' }.@id", is("reason"));
    }

    @Test
    void theReasonsCountTheGroupsOfTheChosenVerdictAndThoseWithNoneAreDisabled() {
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=keep&reason=keep-override")
                .then()
                .body(
                        options("reason", "text()"),
                        contains(
                                "Any reason (3)",
                                "Unregistered (0)",
                                "Unmanaged (0)",
                                "Missing data (0)",
                                "Local error (0)",
                                "Verifying (1)",
                                "Not finished (1)",
                                "Keep override (0)",
                                "Library link (1)",
                                "Minimum not reached (0)",
                                "All met (0)"))
                // The chosen reason stays enabled even with no groups, so the form can still say what it shows.
                .body(
                        "html.body.main.form.'**'.find { it.name() == 'select' }.option"
                                + ".findAll { it.@disabled == 'disabled' }.@value",
                        contains(
                                "unregistered",
                                "unmanaged",
                                "missing-data",
                                "local-error",
                                "minimum-not-reached",
                                "all-met"));
    }

    @Test
    void pageCountsTheGroupsOfEveryVerdictAndLinksToThem() {
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=keep")
                .then()
                .statusCode(200)
                .body(TALLY + ".a.collect { it.text() }", contains("6 All", "1 Remove", "2 Left alone", "3 Keep"))
                .body(
                        TALLY + ".a.@href",
                        contains(
                                "/groups",
                                "/groups?verdict=remove",
                                "/groups?verdict=left-alone",
                                "/groups?verdict=keep"))
                .body(TALLY + ".a.findAll { it.@'aria-current' == 'page' }.@href", is("/groups?verdict=keep"))
                .body(TALLY + ".a.mark.@data-verdict", contains("remove", "left-alone", "keep"))
                .body("html.body.main.div.table.tbody.@data-verdict", contains("keep", "keep", "keep"))
                .body(
                        GroupsTable.GROUPS + ".collect { it.tr[0].td[1].@'data-verdict'.text() }",
                        contains("keep", "keep", "keep"));
    }

    @Test
    void theCountsOfTheVerdictsKeepTheReasonOfTheFilter() {
        Authenticated.asUser()
                .when()
                .get("/groups?reason=missing-data")
                .then()
                .statusCode(200)
                .body(TALLY + ".a.collect { it.text() }", contains("1 All", "0 Remove", "1 Left alone", "0 Keep"))
                .body(TALLY + ".a[2].@href", is("/groups?verdict=left-alone&reason=missing-data"))
                .body(TALLY + ".a.findAll { it.@'aria-current' == 'page' }.@href", is("/groups?reason=missing-data"));
    }

    @Test
    void pageOffersEveryReasonAsAFilter() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .body("html.body.main.form.@method", is("get"))
                .body("html.body.main.form.@action", is("/groups"))
                .body(
                        options("reason", "@value"),
                        contains(
                                "",
                                "unregistered",
                                "unmanaged",
                                "missing-data",
                                "local-error",
                                "verifying",
                                "not-finished",
                                "keep-override",
                                "library-link",
                                "minimum-not-reached",
                                "all-met"))
                .body(
                        options("reason", "text()"),
                        contains(
                                "Any reason (6)",
                                "Unregistered (0)",
                                "Unmanaged (0)",
                                "Missing data (1)",
                                "Local error (1)",
                                "Verifying (1)",
                                "Not finished (1)",
                                "Keep override (0)",
                                "Library link (1)",
                                "Minimum not reached (0)",
                                "All met (1)"))
                .body(VERDICT, is(""))
                .body(selected("reason"), is(""));
    }

    @Test
    void pageSaysSoWhenNoGroupMatchesTheFilter() {
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=remove&reason=library-link")
                .then()
                .statusCode(200)
                .body("html.body.main.'**'.findAll { it.name() == 'table' }.size()", is(0))
                .body("html.body.main.p.size()", is(1))
                .body("html.body.main.p", is("No group has this verdict and reason."))
                .body(selected("reason"), is("library-link"));
    }

    @Test
    void anUnknownVerdictIsSaidAboveTheWholeView() {
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=delete")
                .then()
                .statusCode(200)
                .body(
                        "html.body.main.p.find { it.@role == 'alert' }",
                        is("Unknown verdict 'delete': use remove, left-alone or keep"))
                .body(TALLY + ".a.collect { it.text() }", contains("6 All", "1 Remove", "2 Left alone", "3 Keep"))
                .body(TALLY + ".a.findAll { it.@'aria-current' == 'page' }.@href", is("/groups"))
                .body(GroupsTable.GROUPS + ".size()", is(6))
                .body(VERDICT, is(""))
                .body(selected("reason"), is(""));
    }

    @Test
    void anUnknownFilterIsLeftOutOfTheLinksAndTheOrderIsKept() {
        Authenticated.asUser()
                .when()
                .get("/groups?reason=libary-link&q=movie&sort=name&dir=desc")
                .then()
                .statusCode(200)
                .body(
                        "html.body.main.p.find { it.@role == 'alert' }.text()",
                        startsWith("Unknown reason 'libary-link': use unregistered,"))
                // The whole view, by name the other way round: the name searched for goes with the unknown reason.
                .body(GroupsTable.GROUPS + ".size()", is(6))
                .body(TALLY + ".a[3].@href", is("/groups?verdict=keep&sort=name&dir=desc"))
                .body(TALLY + ".a.findAll { it.@'aria-current' == 'page' }.@href", is("/groups?sort=name&dir=desc"))
                .body("html.body.main.form.'**'.find { it.@name == 'q' }.@value", is(""))
                .body("html.body.main.form.'**'.find { it.@name == 'sort' }.@value", is("name"))
                .body("html.body.main.form.'**'.find { it.@name == 'dir' }.@value", is("desc"));
    }

    @Test
    void theNameSearchedForNarrowsTheCountsOfTheReasonsAndKeepsTheOrderInTheClearLink() {
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=keep&q=KEPT&sort=size&dir=desc")
                .then()
                .statusCode(200)
                .body(
                        options("reason", "text()"),
                        contains(
                                "Any reason (1)",
                                "Unregistered (0)",
                                "Unmanaged (0)",
                                "Missing data (0)",
                                "Local error (0)",
                                "Verifying (0)",
                                "Not finished (0)",
                                "Keep override (0)",
                                "Library link (1)",
                                "Minimum not reached (0)",
                                "All met (0)"))
                .body("html.body.main.form.'**'.find { it.name() == 'a' }.@href", is("/groups?sort=size&dir=desc"));
    }

    @Test
    void anUnknownReasonIsSaidAboveTheWholeView() {
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=keep&reason=libary-link")
                .then()
                .statusCode(200)
                .body(
                        "html.body.main.p.find { it.@role == 'alert' }.text()",
                        startsWith("Unknown reason 'libary-link': use unregistered,"))
                .body(TALLY + ".a.collect { it.text() }", contains("6 All", "1 Remove", "2 Left alone", "3 Keep"))
                .body(GroupsTable.GROUPS + ".size()", is(6))
                .body(VERDICT, is(""))
                .body(selected("reason"), is(""));
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
    private static String torrent(
            String hash, String name, int status, double percentDone, int error, long secondsSeeding, String... files) {
        StringBuilder fileList = new StringBuilder();
        StringBuilder fileStats = new StringBuilder();
        for (String file : files) {
            fileList.append(fileList.isEmpty() ? "" : ",")
                    .append("{\"name\":\"")
                    .append(file)
                    .append("\",\"length\":4}");
            fileStats.append(fileStats.isEmpty() ? "" : ",").append("{\"wanted\":true}");
        }
        return """
                {"hash_string": "%s", "name": "%s", "status": %d, "percent_done": %s, "error": %d,
                 "error_string": "%s", "seconds_seeding": %d, "is_private": true, "download_dir": "%s",
                 "trackers": [{"announce": "https://tracker-a.example/key/announce"}], "files": [%s],
                 "file_stats": [%s]}
                """.formatted(
                        hash,
                        name,
                        status,
                        percentDone,
                        error,
                        error == 0 ? "" : "No data found!",
                        secondsSeeding,
                        FakeTransmissionResource.downloads(),
                        fileList,
                        fileStats);
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }
}
