package io.github.ghostwritertje.seedkeeper.overrides;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmission.Behaviour;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Overrides view, {@code GET /api/v1/overrides} and what the overrides do to the groups, end to end. The
 * overrides file is edited by hand here, while the application runs: it has to pick every edit up by itself.
 *
 * <p>Three groups, all due for removal: {@code Kept} with its cross-seed, {@code Expired} and {@code Free}.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
@Timeout(300)
class OverridesTest {

    private static final long DAY = 86_400;

    private static final String THREE_OVERRIDES = """
            overrides:
              - id: 7f3c
                hashes: [0a0a16]
                note: "Rewatching this season"
                until: 2999-12-31
                created: 2026-11-20T10:12:00+01:00
              - id: 9d2e
                hashes: [0c0c16]
                note: Was on loan
                until: 2020-01-01
              - id: 1b5a
                hashes: [0d0d16, 0e0e16]
            """;

    private Path file;

    @BeforeEach
    void torrentsAndOverrides() throws Exception {
        Path downloads = FakeTransmissionResource.downloads();
        Path original = file(downloads.resolve("Kept/movie.mkv"));
        Path link = downloads.resolve("Kept.Elsewhere/movie.mkv");
        Files.createDirectories(link.getParent());
        if (!Files.isSymbolicLink(link)) {
            Files.createSymbolicLink(link, original);
        }
        file(downloads.resolve("Expired/movie.mkv"));
        file(downloads.resolve("Free/movie.mkv"));
        FakeTransmissionResource.fake().reset();
        FakeTransmissionResource.fake()
                .torrents("["
                        + String.join(
                                ",",
                                torrent("0a0a16", "Kept", "Kept/movie.mkv"),
                                torrent("0b0b16", "Kept.Elsewhere", "Kept.Elsewhere/movie.mkv"),
                                torrent("0c0c16", "Expired", "Expired/movie.mkv"),
                                torrent("0f0f16", "Free", "Free/movie.mkv"))
                        + "]");

        file = FakeTransmissionResource.dataDir().resolve("overrides.yaml");
        edit(THREE_OVERRIDES);
        awaitIds("7f3c", "9d2e", "1b5a");
    }

    /** Other test classes run against the same application: leave them no overrides. */
    @AfterEach
    void noOverrides() throws IOException {
        FakeTransmissionResource.fake().reset();
        Files.deleteIfExists(file);
    }

    @Test
    void apiGivesEveryOverrideMarkedActiveExpiredOrStaleWithTheGroupsItMatches() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/overrides")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("id", contains("7f3c", "9d2e", "1b5a"))
                .body("hashes", contains(List.of("0a0a16"), List.of("0c0c16"), List.of("0d0d16", "0e0e16")))
                .body("note", contains("Rewatching this season", "Was on loan", ""))
                .body("until", contains("2999-12-31", "2020-01-01", null))
                .body("created", contains("2026-11-20T10:12:00+01:00", null, null))
                .body("active", contains(true, false, false))
                .body("expired", contains(false, true, false))
                .body("stale", contains(false, false, true))
                .body("[0].groups.size()", is(1))
                .body("[0].groups[0].hash", contains("0a0a16", "0b0b16"))
                .body("[0].groups[0].name", contains("Kept", "Kept.Elsewhere"))
                .body("[1].groups[0].name", contains("Expired"))
                .body("[2].groups", empty());
    }

    @Test
    void onlyTheActiveOverrideKeepsItsGroup() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .statusCode(200)
                .body("reason", contains("keep-override", "all-met", "all-met"))
                .body("verdict", contains("keep", "remove", "remove"))
                .body("[0].forecast.until", is("2999-12-31"))
                .body("[0].forecast.date", nullValue())
                .body("[0].forecast.clockStopped", is(false))
                .body("[1].forecast", nullValue());

        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body("html.body.main.table.tbody.tr[0].td[6]", is("Keep"))
                .body("html.body.main.table.tbody.tr[0].td[7]", is("Keep override"))
                .body("html.body.main.table.tbody.tr[0].td[9]", is("until 2999-12-31"));
    }

    @Test
    void anExpiredAndStaleOverrideIsMarkedBoth() throws Exception {
        edit("overrides:\n  - id: both\n    hashes: [0d0d16]\n    until: 2020-01-01\n");
        awaitIds("both");

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/overrides")
                .then()
                .body("[0].active", is(false))
                .body("[0].expired", is(true))
                .body("[0].stale", is(true));
        Authenticated.asUser()
                .when()
                .get("/overrides")
                .then()
                .body("html.body.main.table.tbody.tr[0].td[0]", is("Expired, stale"));
    }

    @Test
    void pageShowsEveryOverrideWithItsStatusAndTheGroupItMatches() {
        Authenticated.asUser()
                .when()
                .get("/overrides")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body("html.head.title", is("Overrides · seedkeeper"))
                .body(
                        "html.body.main.table.thead.tr.th",
                        contains("Status", "Note", "Until", "Created", "Group", "Hashes", "Id", "Actions"))
                .body("html.body.main.table.tbody.tr.size()", is(3))
                // The created date is shown in the configured time zone, UTC+05:30.
                .body("html.body.main.table.tbody.tr[0].td[0]", is("Active"))
                .body("html.body.main.table.tbody.tr[0].td[1]", is("Rewatching this season"))
                .body("html.body.main.table.tbody.tr[0].td[2]", is("2999-12-31"))
                .body("html.body.main.table.tbody.tr[0].td[3]", is("2026-11-20 14:42"))
                .body("html.body.main.table.tbody.tr[0].td[4].ul.li", contains("Kept", "Kept.Elsewhere"))
                .body("html.body.main.table.tbody.tr[0].td[5]", is("0a0a16"))
                .body("html.body.main.table.tbody.tr[0].td[6]", is("7f3c"))
                .body("html.body.main.table.tbody.tr[1].td[0]", is("Expired"))
                .body("html.body.main.table.tbody.tr[1].td[2]", is("2020-01-01"))
                .body("html.body.main.table.tbody.tr[1].td[3]", is(""))
                .body("html.body.main.table.tbody.tr[1].td[4].ul.li", is("Expired"))
                .body("html.body.main.table.tbody.tr[2].td[0]", is("Stale"))
                .body("html.body.main.table.tbody.tr[2].td[2]", is("no end date"))
                .body("html.body.main.table.tbody.tr[2].td[4]", is("no torrent"))
                .body("html.body.main.table.tbody.tr[2].td[5]", is("0d0d16, 0e0e16"));
    }

    @Test
    void pageSaysSoWhenThereAreNoOverrides() throws Exception {
        Files.delete(file);
        awaitIds();

        Authenticated.asUser()
                .when()
                .get("/overrides")
                .then()
                .statusCode(200)
                .body("html.body.main.p[0]", is("There are no keep overrides."))
                .body("html.body.main.'**'.findAll { it.name() == 'table' }.size()", is(0));
    }

    @Test
    void aFileThatCannotBeReadIsReportedAndThereAreNoVerdicts() throws Exception {
        edit("overrides:\n  - id: 7f3c\n");
        String reason = "The overrides file " + file + " can't be read: override 1 (7f3c) has no 'hashes'";
        await(response -> response.statusCode() == 503);

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/overrides")
                .then()
                .statusCode(503)
                .contentType("application/json")
                .body("reason", is(reason));
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .statusCode(503)
                .body("reason", is(reason));
        Authenticated.asUser()
                .when()
                .get("/overrides")
                .then()
                .statusCode(200)
                .body("html.body.main.p.find { it.@role == 'alert' }", is(reason))
                .body("html.body.main.'**'.findAll { it.name() == 'table' }.size()", is(0));

        // Repaired by hand: read again without a restart.
        edit(THREE_OVERRIDES);
        awaitIds("7f3c", "9d2e", "1b5a");
    }

    @Test
    void whileTransmissionCannotBeUsedTheOverridesAreListedWithTheirGroupsUnknown() {
        FakeTransmissionResource.fake().behaviour(Behaviour.RPC_ERROR);

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/overrides")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("id", contains("7f3c", "9d2e", "1b5a"))
                .body("expired", contains(false, true, false))
                .body("active", contains(nullValue(), is(false), nullValue()))
                .body("stale", contains(nullValue(), nullValue(), nullValue()))
                .body("groups", contains(nullValue(), nullValue(), nullValue()))
                .body("[0].groupsUnknown", containsString("refused session_get: Method not found"))
                .body("[1].groupsUnknown", containsString("refused session_get: Method not found"));
        Authenticated.asUser()
                .when()
                .get("/overrides")
                .then()
                .statusCode(200)
                .body(
                        "html.body.main.p.find { it.@role == 'alert' }",
                        containsString("refused session_get: Method not found"))
                .body("html.body.main.table.tbody.tr.size()", is(3))
                .body("html.body.main.table.tbody.tr[0].td[0]", is("Unknown"))
                .body("html.body.main.table.tbody.tr[0].td[4]", is("unknown"))
                .body("html.body.main.table.tbody.tr[1].td[0]", is("Expired"))
                .body("html.body.main.table.tbody.tr[0].td[7].form.size()", is(2))
                .body("html.body.main.section.find { it.@id == 'add-override' }.form.@id", is("add"));
    }

    @Test
    void whenTheGroupsAreKnownNoOverrideSaysTheyAreUnknown() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/overrides")
                .then()
                .statusCode(200)
                .body("groupsUnknown", contains(nullValue(), nullValue(), nullValue()));
    }

    @Test
    void theNavigationLeadsToTheOverridesView() {
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .body("html.body.header.nav.a.find { it.@href == '/overrides' }", is("Overrides"));
    }

    @Test
    void theApiNeedsTheApiKeyAndThePageALogin() {
        io.restassured.RestAssured.given()
                .when()
                .get("/api/v1/overrides")
                .then()
                .statusCode(401);
        io.restassured.RestAssured.given()
                .redirects()
                .follow(false)
                .when()
                .get("/overrides")
                .then()
                .statusCode(302);
    }

    /** A hand edit: the file is overwritten where it is, as an editor would. */
    private void edit(String yaml) throws IOException {
        Files.writeString(file, yaml);
    }

    /** Waits until the application has read the file and gives the overrides with these ids, in this order. */
    private static void awaitIds(String... ids) throws InterruptedException {
        await(response -> response.statusCode() == 200
                && response.jsonPath().getList("id").equals(List.of(ids)));
    }

    /** The file watcher of the JDK polls on macOS, so this can take a few seconds. */
    private static void await(java.util.function.Predicate<Response> seen) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        Response last;
        do {
            last = Authenticated.withApiKey().when().get("/api/v1/overrides");
            if (seen.test(last)) {
                return;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("The application did not pick up the edit to the overrides file; it still answers "
                + last.statusCode() + " " + last.asString());
    }

    /** A finished, seeding torrent of tracker-a that has met its minimum: due for removal. */
    private static String torrent(String hash, String name, String file) {
        return """
                {"hash_string": "%s", "name": "%s", "status": 6, "percent_done": 1, "error": 0, "error_string": "",
                 "seconds_seeding": %d, "is_private": true, "download_dir": "%s",
                 "trackers": [{"announce": "https://tracker-a.example/announce"}],
                 "files": [{"name": "%s", "length": 4}], "file_stats": [{"wanted": true}]}
                """.formatted(hash, name, 400 * DAY, FakeTransmissionResource.downloads(), file);
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }
}
