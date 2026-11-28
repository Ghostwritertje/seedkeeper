package io.github.ghostwritertje.seedkeeper.groups;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
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
    /**
     * The verdict the filter form keeps when the reason changes: a lane's "Show all" link chooses it, the form has no
     * control for it.
     */
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
                .body(GroupsLanes.names("keep"), contains("Checking.Movie", "Kept.Movie", "Not.Yet"))
                // Left alone by reason, in the order of precedence.
                .body(GroupsLanes.names("left-alone"), contains("Half.Gone", "Broken.Movie"))
                .body(GroupsLanes.names("remove"), is("Done.Movie"))
                // The file that is gone still counts: the size is what Transmission says the data is.
                .body(GroupsLanes.summary("left-alone", 0), is("tracker-a · Seeding · 0 of 2 files linked · 8 B"))
                .body(GroupsLanes.reason("left-alone", 0), is("Missing data (1 of 2 files)"))
                .body(
                        GroupsLanes.hint("left-alone", 0),
                        is("files are gone from disk: re-verify or remove in Transmission"))
                .body(GroupsLanes.secondaryReasons("left-alone", 0), is(""))
                .body(GroupsLanes.reason("left-alone", 1), is("Local error"))
                .body(GroupsLanes.secondaryReasons("left-alone", 1), is("also: Minimum not reached"))
                .body(GroupsLanes.progress("left-alone", 1), is("3% of minimum"))
                .body(GroupsLanes.summary("keep", 0), is("tracker-a · Verifying · 0 of 1 files linked · 4 B"))
                .body(GroupsLanes.reason("keep", 0), is("Verifying"))
                .body(GroupsLanes.reason("keep", 2), is("Not finished"))
                .body(GroupsLanes.secondaryReasons("keep", 2), is("also: Minimum not reached"));
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
                .body(GroupsLanes.summary("left-alone", 0), is("tracker-a · Verifying · 1 of 1 files linked · 4 B"))
                .body(GroupsLanes.reason("left-alone", 0), is("Local error"))
                .body(
                        GroupsLanes.secondaryReasons("left-alone", 0),
                        is("also: Verifying, Library link, Minimum not reached"));
    }

    @Test
    void pageFiltersByVerdictAndReason() {
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=keep")
                .then()
                .statusCode(200)
                .body(GroupsLanes.HEADINGS, is("3 Keep"))
                .body(GroupsLanes.names("keep"), contains("Checking.Movie", "Kept.Movie", "Not.Yet"))
                .body(VERDICT, is("keep"))
                .body(selected("reason"), is(""));
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=left-alone&reason=missing-data")
                .then()
                .body(GroupsLanes.CARDS + ".size()", is(1))
                .body(GroupsLanes.names("left-alone"), is("Half.Gone"))
                .body(VERDICT, is("left-alone"))
                .body(selected("reason"), is("missing-data"));
    }

    @Test
    void theReasonNarrowsEveryLane() {
        Authenticated.asUser()
                .when()
                .get("/groups?reason=missing-data")
                .then()
                .statusCode(200)
                .body(GroupsLanes.HEADINGS, contains("0 Keep", "1 Left alone", "0 Remove"))
                .body(GroupsLanes.names("left-alone"), is("Half.Gone"))
                .body(GroupsLanes.lane("keep") + ".a.size()", is(0));
    }

    @Test
    void theVerdictIsChosenOnlyByTheLanes() {
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
                .body("html.body.main.form.@'hx-select'", is("main"))
                .body("html.body.main.form.button.size()", is(0))
                .body("html.body.main.form.noscript.button", is("Filter"))
                .body("html.body.main.form.'**'.find { it.name() == 'select' }.@id", is("reason"));
    }

    @Test
    void theFilterFormsAttributesAreSeparatedByWhitespace() {
        // The HTML parser recovers from attributes with no whitespace between them, so look at the raw markup.
        String html = Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertThat(html, containsString("hx-trigger=\"change, submit\" hx-select=\"main\""));
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
    void theVerdictChipsAreGoneTheLaneHeadingsCountTheVerdicts() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body("html.body.main.nav.findAll { it.@'aria-label' == 'Groups by verdict' }.size()", is(0))
                .body(GroupsLanes.HEADINGS, contains("3 Keep", "2 Left alone", "1 Remove"));
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
                .body("html.body.main.'**'.findAll { it.@id == 'lanes' }.size()", is(0))
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
                .body(GroupsLanes.HEADINGS, contains("3 Keep", "2 Left alone", "1 Remove"))
                .body(GroupsLanes.CARDS + ".size()", is(6))
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
                .body(GroupsLanes.CARDS + ".size()", is(6))
                .body(GroupsLanes.names("keep"), contains("Not.Yet", "Kept.Movie", "Checking.Movie"))
                .body("html.body.main.form.'**'.find { it.@name == 'q' }.@value", is(""))
                .body(selected("sort"), is("name"))
                .body("html.body.main.form.'**'.find { it.@name == 'dir' }.@checked.text()", is("checked"));
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
                .body(
                        "html.body.main.form.'**'.find { it.name() == 'a' && it.text() == 'Clear filter' }.@href",
                        is("/groups?sort=size&dir=desc"));
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
                .body(GroupsLanes.HEADINGS, contains("3 Keep", "2 Left alone", "1 Remove"))
                .body(GroupsLanes.CARDS + ".size()", is(6))
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
