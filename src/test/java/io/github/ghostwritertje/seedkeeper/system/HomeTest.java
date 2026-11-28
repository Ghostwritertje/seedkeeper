package io.github.ghostwritertje.seedkeeper.system;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The overview at the bare URL, end to end: the verdict groups of the fake Transmission, the runs and the warnings,
 * each number linking to the view that lists it. tracker-a asks for 30 days of seed time, tracker-b for 14.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class HomeTest {

    private static final long DAY = 86_400;
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String VERDICTS = "html.body.main.section.find { it.@id == 'verdicts' }";
    private static final String DUE_SOON = "html.body.main.section.find { it.@id == 'due-soon' }";
    private static final String RUNS = "html.body.main.section.find { it.@id == 'runs' }";
    private static final String WARNINGS = "html.body.main.section.find { it.@id == 'warnings' }";

    @BeforeEach
    void oneGroupOfEveryVerdict() throws IOException {
        Path downloads = FakeTransmissionResource.downloads();
        for (String name : new String[] {"Home.Done", "Home.Soon", "Home.Paused", "Home.Unknown"}) {
            file(downloads.resolve(name + "/movie.mkv"));
        }
        FakeTransmissionResource.fake().reset();
        FakeTransmissionResource.fake()
                .torrents("["
                        + String.join(
                                ",",
                                torrent("home1", "Home.Done", 6, 31 * DAY, "https://tracker-a.example/key/announce"),
                                torrent("home2", "Home.Soon", 6, 13 * DAY, "https://tracker-b.example/announce"),
                                torrent("home3", "Home.Paused", 0, DAY, "https://tracker-b.example/announce"),
                                torrent("home4", "Home.Unknown", 6, 400 * DAY, "https://elsewhere.example/announce"))
                        + "]");
    }

    @Test
    void bareUrlWithoutLoginRedirectsToTheLoginForm() {
        given().redirects().follow(false).when().get("/").then().statusCode(302).header("Location", endsWith("/login"));
    }

    @Test
    void theOverviewIsAtTheBareUrlAndTheLogoLinksToIt() {
        Authenticated.asUser()
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body("html.head.title", is("Overview · seedkeeper"))
                .body("html.body.main.h1", is("Overview"));
        Authenticated.asUser().when().get("/groups").then().statusCode(200).body("html.body.aside.a[0].@href", is("/"));
    }

    @Test
    void theVerdictCountsLinkToTheGroupsViewFilteredByThatVerdict() {
        Authenticated.asUser()
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body(
                        VERDICTS + ".nav.a.collect { it.@href }",
                        contains(
                                "/groups",
                                "/groups?verdict=remove",
                                "/groups?verdict=left-alone",
                                "/groups?verdict=keep"))
                .body(VERDICTS + ".nav.a.collect { it.mark.text() }", contains("4", "1", "1", "2"))
                .body(
                        VERDICTS + ".nav.a.collect { it.text() }",
                        contains("4 All", "1 Remove", "1 Left alone", "2 Keep"));
    }

    @Test
    void dueSoonListsTheKeptGroupsWhoseForecastFallsSoonAndLinksToThem() {
        Authenticated.asUser()
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body(DUE_SOON + ".p.a.@href", is("/groups?verdict=keep&sort=forecast"))
                .body(DUE_SOON + ".p.a", is("1 kept group"))
                .body(DUE_SOON + ".table.tbody.tr.th.a", is("Home.Soon"))
                .body(DUE_SOON + ".table.tbody.tr.th.a.@href", is("/groups?q=Home.Soon"))
                .body(DUE_SOON + ".table.tbody.tr.td.time", matchesPattern("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d"))
                .body(
                        DUE_SOON + ".table.tbody.tr.td.time.@datetime",
                        matchesPattern("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+05:30"));
    }

    @Test
    void theLastRunLinksWhatItRemovedToTheActivityViewAndTheNextRunIsShown() {
        runNow();

        Authenticated.asUser()
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body(RUNS + ".dl.dt.collect { it.text() }", contains("Last run", "Removed", "Space freed", "Next run"))
                .body(RUNS + ".dl.dd[0].mark", is("Succeeded"))
                .body(RUNS + ".dl.dd[0].time.@datetime", matchesPattern("\\d{4}-\\d\\d-\\d\\dT.+\\+05:30"))
                .body(RUNS + ".dl.dd[1].a", is("1 torrent in 1 group"))
                .body(RUNS + ".dl.dd[1].a.@href", is("/activity"))
                .body(RUNS + ".dl.dd[2]", is("4 B"))
                .body(RUNS + ".dl.dd[3].time", matchesPattern("\\d{4}-01-01 06:00:00"));
    }

    @Test
    void aFailedRunAndATransmissionSeedkeeperCannotUseAreWarnedAbout() {
        FakeTransmissionResource.fake().version("4.0.6 (38c164933e)");
        runNow();

        Response page = Authenticated.asUser().when().get("/");

        page.then()
                .statusCode(200)
                .body(WARNINGS + ".ul.li.a.@href", hasItem("/system"))
                .body(WARNINGS + ".ul.li.a.@href", hasItem("/activity"))
                .body(
                        WARNINGS + ".ul.li.a.collect { it.text() }",
                        hasItem(matchesPattern("Transmission: .* is older than 4\\.1.*")))
                .body(
                        WARNINGS + ".ul.li.a.collect { it.text() }",
                        hasItem(matchesPattern("The last run failed: .* is older than 4\\.1.*")))
                .body(VERDICTS + ".p.@role", is("alert"))
                .body("html.body.main.section.collect { it.@id }", not(hasItem("due-soon")));
    }

    @Test
    void groupsLeftAloneAreWarnedAboutAndLinkToTheGroupsViewFilteredByIt() {
        Authenticated.asUser()
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body(
                        WARNINGS + ".ul.li.a.find { it.@href == '/groups?verdict=left-alone' }",
                        is("1 group is left alone: someone should look at it"));
    }

    /** Presses Run now on the System view as a logged-in user. */
    private static void runNow() {
        Cookie session = Authenticated.login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Response page = given().cookie(session).when().get("/system");
        String token = page.htmlPath().getString(RUNS + ".form.input.find { it.@name == 'csrf-token' }.@value");
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

    /** A finished, private torrent with one wanted file. */
    private static String torrent(String hash, String name, int status, long secondsSeeding, String announce) {
        return """
                {"hash_string": "%s", "name": "%s", "status": %d, "percent_done": 1, "error": 0, "error_string": "",
                 "seconds_seeding": %d, "is_private": true, "download_dir": "%s",
                 "trackers": [{"announce": "%s"}],
                 "files": [{"name": "%s/movie.mkv", "length": 4}], "file_stats": [{"wanted": true}]}
                """.formatted(hash, name, status, secondsSeeding, FakeTransmissionResource.downloads(), announce, name);
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }
}
