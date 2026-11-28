package io.github.ghostwritertje.seedkeeper.system;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.TestSecrets;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import io.restassured.response.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The sidebar of every page: the views in three labelled sections, and its status block, which the page loads after
 * itself from {@code GET /status}. tracker-a asks for 30 days of seed time.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class NavigationTest {

    private static final String NAV = "html.body.aside.nav.find { it.@'aria-label' == 'Views' }";
    private static final String STATUS = "html.body.dl";
    private static final String BADGE = "html.body.span.find { it.@id == 'left-alone' }";
    private static final String CSRF_COOKIE = "csrf-token";

    @BeforeEach
    void healthyTransmission() {
        FakeTransmissionResource.fake().reset();
    }

    @Test
    void theSidebarListsTheViewsInThreeLabelledSections() {
        Authenticated.asUser()
                .when()
                .get("/settings")
                .then()
                .statusCode(200)
                .body(NAV + ".h2.collect { it.text() }", contains("Day to day", "Tools", "Setup"))
                .body(NAV + ".ul[0].li.a.collect { it.@href }", contains("/", "/groups", "/activity"))
                .body(NAV + ".ul[1].li.a.collect { it.@href }", contains("/overrides", "/orphans"))
                .body(NAV + ".ul[2].li.a.collect { it.@href }", contains("/settings", "/system"))
                .body(
                        NAV + ".ul.li.a.collect { it.text() }",
                        contains("Overview", "Groups", "Activity", "Keep overrides", "Orphans", "Settings", "System"));
    }

    @Test
    void theSidebarMarksTheViewThatIsShown() {
        Authenticated.asUser()
                .when()
                .get("/activity")
                .then()
                .statusCode(200)
                .body(NAV + ".ul.li.a.findAll { it.@'aria-current' == 'page' }.@href", is("/activity"));
    }

    @Test
    void theSidebarKeepsTheBrandLinkingToTheOverviewAndLogOut() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body("html.body.aside.a[0].@href", is("/"))
                .body("html.body.aside.form.@action", is("/logout"));
    }

    @Test
    void aPageAsksTransmissionNothingAndLoadsTheStatusAfterItselfEveryMinute() {
        Authenticated.asUser()
                .when()
                .get("/settings")
                .then()
                .statusCode(200)
                .body("html.body.aside.div.find { it.@id == 'status' }.@hx-get", is("/status"))
                .body("html.body.aside.div.find { it.@id == 'status' }.@hx-trigger", is("load, every 60s"))
                .body("html.body.aside.div.find { it.@id == 'status' }.children().size()", is(0));

        assertEquals(List.of(), FakeTransmissionResource.fake().requests());
    }

    @Test
    void theStatusSaysTransmissionIsReachableAndWhenTheNextAndLastRunsAre() {
        runNow();

        Authenticated.asUser()
                .when()
                .get("/status")
                .then()
                .statusCode(200)
                .body(STATUS + ".dt.collect { it.text() }", contains("Transmission", "Next run", "Last run"))
                .body(STATUS + ".dd[0]", is("Reachable"))
                .body(STATUS + ".dd[0].@data-tone", is("ok"))
                // The test schedule runs on 1 January at 06:00: always days away, at least one more month.
                .body(STATUS + ".dd[1].time", matchesPattern("in \\d+d( \\d+h)?"))
                .body(STATUS + ".dd[1].time.@datetime", matchesPattern("\\d{4}-01-01T06:00:00\\+05:30"))
                .body(STATUS + ".dd[2].time", matchesPattern("just now|\\d+m ago"))
                .body(STATUS + ".dd[2].time.@title", matchesPattern("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d .+"));
    }

    @Test
    void theStatusSaysWhenTransmissionIsReachableButNotUsable() {
        FakeTransmissionResource.fake().version("4.0.6 (38c164933e)");

        Authenticated.asUser()
                .when()
                .get("/status")
                .then()
                .statusCode(200)
                .body(STATUS + ".dd[0]", is("Reachable, not usable"))
                .body(STATUS + ".dd[0].@data-tone", is("warn"));
    }

    @Test
    void theGroupsBadgeCountsTheGroupsLeftAlone() throws IOException {
        Path downloads = FakeTransmissionResource.downloads();
        file(downloads.resolve("Nav.Unknown1/movie.mkv"));
        file(downloads.resolve("Nav.Unknown2/movie.mkv"));
        file(downloads.resolve("Nav.Kept/movie.mkv"));
        FakeTransmissionResource.fake()
                .torrents("["
                        + String.join(
                                ",",
                                torrent("nav1", "Nav.Unknown1", "https://elsewhere.example/announce"),
                                torrent("nav2", "Nav.Unknown2", "https://elsewhere.example/announce"),
                                torrent("nav3", "Nav.Kept", "https://tracker-a.example/key/announce"))
                        + "]");

        Authenticated.asUser()
                .when()
                .get("/status")
                .then()
                .statusCode(200)
                .body(BADGE + ".@hx-swap-oob", is("true"))
                .body(BADGE + ".mark.@data-verdict", is("left-alone"))
                // The words are for a screen reader, which reads the link as "Groups 2 left alone".
                .body(containsString(
                        "<mark data-verdict=\"left-alone\">2<span data-visually-hidden> left alone</span></mark>"));
    }

    @Test
    void theGroupsBadgeIsAbsentWhenNoGroupIsLeftAlone() throws IOException {
        file(FakeTransmissionResource.downloads().resolve("Nav.Kept/movie.mkv"));
        FakeTransmissionResource.fake()
                .torrents("[" + torrent("nav3", "Nav.Kept", "https://tracker-a.example/key/announce") + "]");

        Authenticated.asUser()
                .when()
                .get("/status")
                .then()
                .statusCode(200)
                .body(BADGE + ".@hx-swap-oob", is("true"))
                .body(BADGE + ".children().list()", empty());
    }

    /** Presses Run now on the System view as a logged-in user. */
    private static void runNow() {
        Cookie session = Authenticated.login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Response page = given().cookie(session).when().get("/system");
        String token = page.htmlPath()
                .getString("html.body.main.section.find { it.@id == 'runs' }.form.input"
                        + ".find { it.@name == 'csrf-token' }.@value");
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

    /** A finished, private torrent that has seeded for a day, with one wanted file. */
    private static String torrent(String hash, String name, String announce) {
        return """
                {"hash_string": "%s", "name": "%s", "status": 6, "percent_done": 1, "error": 0, "error_string": "",
                 "seconds_seeding": 86400, "is_private": true, "download_dir": "%s",
                 "trackers": [{"announce": "%s"}],
                 "files": [{"name": "%s/movie.mkv", "length": 4}], "file_stats": [{"wanted": true}]}
                """.formatted(hash, name, FakeTransmissionResource.downloads(), announce, name);
    }

    private static void file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "data");
    }
}
