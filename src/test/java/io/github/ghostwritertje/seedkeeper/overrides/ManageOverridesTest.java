package io.github.ghostwritertje.seedkeeper.overrides;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Adding, editing and deleting keep overrides in the UI, end to end: the forms of the Overrides and Groups views,
 * the overrides file and the verdicts.
 *
 * <p>Two groups, both due for removal: {@code Movie} with its cross-seed, and {@code Show}.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
@Timeout(300)
class ManageOverridesTest {

    private static final long DAY = 86_400;
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String ROWS = "html.body.main.div.table.tbody.tr";
    private static final String DONE = "html.body.main.p.find { it.@id == 'done' }";
    private static final String DONE_COUNT = "html.body.main.p.findAll { it.@id == 'done' }.size()";
    private static final String SELECTED_VERDICT = "html.body.main.form.'**'.find { it.name() == 'select'"
            + " && it.@name == 'verdict' }.option.find { it.@selected == 'selected' }.@value";

    private Path file;

    @BeforeEach
    void twoDueGroupsAndNoOverrides() throws Exception {
        Path downloads = FakeTransmissionResource.downloads();
        Path original = file(downloads.resolve("Manage.Movie/movie.mkv"));
        Path link = downloads.resolve("Manage.cross-seed/Manage.Movie/movie.mkv");
        Files.createDirectories(link.getParent());
        if (!Files.isSymbolicLink(link)) {
            Files.createSymbolicLink(link, original);
        }
        file(downloads.resolve("Manage.Show/show.mkv"));
        FakeTransmissionResource.fake().reset();
        FakeTransmissionResource.fake()
                .torrents("["
                        + String.join(
                                ",",
                                torrent("1a1a17", "Manage.Movie", downloads, "Manage.Movie/movie.mkv"),
                                torrent(
                                        "2b2b17",
                                        "Manage.Movie.cross-seed",
                                        downloads.resolve("Manage.cross-seed"),
                                        "Manage.Movie/movie.mkv"),
                                torrent("3c3c17", "Manage.Show", downloads, "Manage.Show/show.mkv"))
                        + "]");
        file = FakeTransmissionResource.dataDir().resolve("overrides.yaml");
        Files.deleteIfExists(file);
    }

    /** Other test classes run against the same application: leave them no overrides. */
    @AfterEach
    void noOverrides() throws IOException {
        FakeTransmissionResource.fake().reset();
        Files.deleteIfExists(file);
    }

    @Test
    void anOverrideAddedInTheOverridesViewIsWrittenAndKeepsItsGroupRightAway() throws IOException {
        Session user = Session.login();
        Response page = user.page("/overrides");

        user.form(page, "add")
                .formParam("hashes", "3C3C17")
                .formParam("note", "Rewatching \"this\" season")
                .formParam("until", "2999-12-31")
                .post("/overrides")
                .then()
                .statusCode(303)
                .header("Location", endsWith("/overrides?done=added"));
        user.page("/overrides?done=added")
                .then()
                .statusCode(200)
                .body(DONE + ".text()", is("Keep override added."))
                .body(DONE + ".@role", is("status"));

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/overrides")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].id", matchesPattern("[0-9a-f-]{36}"))
                .body("[0].hashes", contains("3c3c17"))
                .body("[0].note", is("Rewatching \"this\" season"))
                .body("[0].until", is("2999-12-31"))
                // Now, in the configured time zone, UTC+05:30.
                .body("[0].created", matchesPattern("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+05:30"))
                .body("[0].active", is(true));
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .body("find { it.members.hash.contains('3c3c17') }.reason", is("keep-override"));
        assertFalse(Files.readString(file).isBlank());
    }

    @Test
    void aKeepOverrideSetFromAGroupHasTheHashOfEveryMemberAndKeepsTheGroupRightAway() {
        Session user = Session.login();
        Response page = user.page("/groups");
        page.then()
                .statusCode(200)
                .body("html.body.main.div.table.thead.tr.th[10]", is("Keep override"))
                .body("**.find { it.name() == 'form' && it.@id == 'keep-1a1a17' }.@action", is("/groups/keep"))
                .body(
                        "**.find { it.name() == 'form' && it.@id == 'keep-1a1a17' }.'**'.find { it.name() == 'button' }",
                        is("Set a keep override"));

        user.form(page, "keep-1a1a17")
                .formParam("note", "Rewatching")
                .formParam("until", "")
                .post("/groups/keep")
                .then()
                .statusCode(303)
                .header("Location", endsWith("/groups?done=kept"));

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/overrides")
                .then()
                .body("size()", is(1))
                .body("[0].hashes", contains("1a1a17", "2b2b17"))
                .body("[0].note", is("Rewatching"))
                .body("[0].until", nullValue())
                .body("[0].created", matchesPattern("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+05:30"));
        user.page("/groups")
                .then()
                .body(ROWS + ".find { it.td[0] == 'Manage.Movie' }.td[7]", is("Keep override"))
                .body(ROWS + ".find { it.td[0] == 'Manage.Show' }.td[7]", is("All met"));
    }

    @Test
    void aKeepOverrideSetFromAFilteredGroupsViewReturnsToTheSameFilterAndSaysSo() {
        Session user = Session.login();
        Response page = user.page("/groups?verdict=remove");
        page.then()
                .statusCode(200)
                .body(hidden("keep-1a1a17", "verdict"), is("remove"))
                .body(hidden("keep-1a1a17", "reason"), is(""))
                .body(hidden("keep-3c3c17", "verdict"), is("remove"))
                .body(hidden("keep-3c3c17", "reason"), is(""))
                .body("html.body.main.p.findAll { it.@id == 'done' }.size()", is(0));

        String location = user.form(page, "keep-1a1a17")
                .formParam("note", "Rewatching")
                .formParam("until", "")
                .post("/groups/keep")
                .then()
                .statusCode(303)
                .extract()
                .header("Location");

        URI uri = URI.create(location);
        assertEquals("/groups", uri.getPath());
        List<String> query = List.of(uri.getQuery().split("&"));
        assertTrue(query.contains("verdict=remove"), location);
        assertTrue(query.contains("done=kept"), location);
        assertTrue(query.stream().noneMatch(p -> p.startsWith("reason=")), location);

        user.page(uri.getPath() + "?" + uri.getQuery())
                .then()
                .statusCode(200)
                .body(
                        "html.body.main.p.find { it.@id == 'done' }.text()",
                        is("Keep override set. It shows in the Overrides view."))
                .body("html.body.main.p.find { it.@id == 'done' }.@role", is("status"))
                .body("html.body.main.p.find { it.@id == 'done' }.a.@href", is("/overrides"))
                .body("html.body.main.p.find { it.@id == 'done' }.a", is("Overrides view"))
                // Kept now, so no longer due: only the other group is left under Remove.
                .body(ROWS + ".size()", is(1))
                .body(ROWS + "[0].td[0]", is("Manage.Show"));
        user.page("/groups?done=other")
                .then()
                .statusCode(200)
                .body("html.body.main.p.findAll { it.@id == 'done' }.size()", is(0));
    }

    @Test
    void aKeepOverrideThatIsRefusedInTheGroupsViewKeepsTheFilter() {
        Session user = Session.login();
        Response page = user.page("/groups?verdict=remove&reason=all-met");
        page.then()
                .body(hidden("keep-3c3c17", "verdict"), is("remove"))
                .body(hidden("keep-3c3c17", "reason"), is("all-met"));

        user.form(page, "keep-3c3c17")
                .formParam("note", "x")
                .formParam("until", "31/12/2999")
                .post("/groups/keep")
                .then()
                .statusCode(400)
                .body(
                        "html.body.main.p.find { it.@id == 'notice' }",
                        is("The end date is not a date like 2027-03-01: 31/12/2999"))
                .body(SELECTED_VERDICT, is("remove"))
                .body(hidden("keep-3c3c17", "reason"), is("all-met"));

        assertFalse(Files.exists(file), "the overrides file was written");
    }

    @Test
    void theOverridesViewSaysNothingIsDoneWithoutAKnownChange() {
        Session user = Session.login();

        for (String path :
                List.of("/overrides", "/overrides?done=", "/overrides?done=kept", "/overrides?done=%3Cscript%3E")) {
            Response page = user.page(path);
            page.then().statusCode(200).body(DONE_COUNT, is(0));
            assertFalse(page.asString().contains("<script>"), path);
        }
    }

    @Test
    void editingChangesTheNoteAndTheEndDateAndKeepsTheRest() throws IOException {
        Files.writeString(file, """
                overrides:
                  - id: hand-made
                    hashes: [3c3c17]
                    note: Old note
                    created: 2026-11-20T10:12:00+01:00
                  - id: other
                    hashes: [1a1a17]
                """);
        Session user = Session.login();
        Response page = awaitPage(user, "hand-made", "other");

        user.form(page, "edit-0")
                .formParam("note", "New note")
                .formParam("until", "2020-01-01")
                .post("/overrides/edit")
                .then()
                .statusCode(303)
                .header("Location", endsWith("/overrides?done=saved"));
        user.page("/overrides?done=saved")
                .then()
                .statusCode(200)
                .body(DONE + ".text()", is("Keep override saved."))
                .body(DONE + ".@role", is("status"));

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/overrides")
                .then()
                .body("id", contains("hand-made", "other"))
                .body("[0].hashes", contains("3c3c17"))
                .body("[0].note", is("New note"))
                .body("[0].until", is("2020-01-01"))
                .body("[0].created", is("2026-11-20T10:12:00+01:00"))
                .body("[0].expired", is(true))
                .body("[1].note", is(""));
        // Expired: the group is due again.
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .body("find { it.members.hash.contains('3c3c17') }.reason", is("all-met"));

        // The end date can be taken away again.
        page = user.page("/overrides");
        user.form(page, "edit-0")
                .formParam("note", "New note")
                .formParam("until", "")
                .post("/overrides/edit")
                .then()
                .statusCode(303);
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/overrides")
                .then()
                .body("[0].until", nullValue())
                .body("[0].active", is(true));
    }

    @Test
    void deletingRemovesTheOverrideAndItsGroupIsDueAgain() throws IOException {
        Files.writeString(file, """
                overrides:
                  - id: first
                    hashes: [3c3c17]
                  - id: second
                    hashes: [1a1a17]
                """);
        Session user = Session.login();
        Response page = awaitPage(user, "first", "second");
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .body("reason", contains("keep-override", "keep-override"));

        user.form(page, "delete-1")
                .post("/overrides/delete")
                .then()
                .statusCode(303)
                .header("Location", endsWith("/overrides?done=deleted"));
        user.page("/overrides?done=deleted")
                .then()
                .statusCode(200)
                .body(DONE + ".text()", is("Keep override deleted."))
                .body(DONE + ".@role", is("status"));

        Authenticated.withApiKey().when().get("/api/v1/overrides").then().body("id", contains("first"));
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .body("find { it.members.hash.contains('1a1a17') }.reason", is("all-met"))
                .body("find { it.members.hash.contains('3c3c17') }.reason", is("keep-override"));
    }

    @Test
    void deletingAsksForConfirmationButSavingAndAddingDoNot() throws IOException {
        Files.writeString(file, "overrides:\n  - id: only\n    hashes: [3c3c17]\n");
        Session user = Session.login();
        Response page = awaitPage(user, "only");

        page.then()
                .body(
                        "**.find { it.name() == 'form' && it.@id == 'delete-0' }.@onsubmit",
                        is("return confirm('Delete this keep override?"
                                + " The next run may then remove the torrents it keeps.')"))
                .body(
                        "**.find { it.name() == 'form' && it.@id == 'edit-0' }.attributes().containsKey('onsubmit')",
                        is(false))
                .body(
                        "**.find { it.name() == 'form' && it.@id == 'add' }.attributes().containsKey('onsubmit')",
                        is(false));
    }

    @Test
    void theControlsOfARowNameTheirOverrideAndKeepTheirVisibleText() throws IOException {
        Files.writeString(file, "overrides:\n  - id: abc\n    hashes: [3c3c17]\n");
        Session user = Session.login();
        Response page = awaitPage(user, "abc");

        page.then()
                .body(
                        "**.find { it.name() == 'form' && it.@id == 'edit-0' }.'**'.findAll { it.@'aria-label' != '' }"
                                + ".collect { it.@'aria-label'.toString() }",
                        contains("Note of keep override abc", "Until of keep override abc", "Save keep override abc"))
                .body(
                        "**.find { it.name() == 'form' && it.@id == 'edit-0' }.'**'.find { it.name() == 'button' }",
                        is("Save"))
                .body(
                        "**.find { it.name() == 'form' && it.@id == 'delete-0' }.'**'.find { it.name() == 'button' }"
                                + ".@'aria-label'",
                        is("Delete keep override abc"))
                .body(
                        "**.find { it.name() == 'form' && it.@id == 'delete-0' }.'**'.find { it.name() == 'button' }",
                        is("Delete"));
    }

    @Test
    void anOverrideWithoutHashesOrWithAWrongDateIsRefusedAndNothingIsWritten() {
        Session user = Session.login();
        Response page = user.page("/overrides");

        user.form(page, "add")
                .formParam("hashes", " , ")
                .formParam("note", "x")
                .formParam("until", "")
                .post("/overrides")
                .then()
                .statusCode(400)
                .body(
                        "html.body.main.p.find { it.@id == 'notice' }",
                        is("A keep override needs at least one info hash."))
                .body(DONE_COUNT, is(0));
        user.form(page, "add")
                .formParam("hashes", "3c3c17")
                .formParam("note", "x")
                .formParam("until", "31/12/2999")
                .post("/overrides")
                .then()
                .statusCode(400)
                .body(
                        "html.body.main.p.find { it.@id == 'notice' }",
                        is("The end date is not a date like 2027-03-01: 31/12/2999"));

        assertFalse(Files.exists(file), "the overrides file was written");
    }

    @Test
    void editingOrDeletingAnOverrideThatIsGoneSaysSo() throws IOException {
        Files.writeString(file, "overrides:\n  - id: gone-soon\n    hashes: [3c3c17]\n");
        Session user = Session.login();
        Response page = awaitPage(user, "gone-soon");
        Files.writeString(file, "overrides:\n  - id: replaced\n    hashes: [3c3c17]\n");

        user.form(page, "edit-0")
                .formParam("note", "x")
                .formParam("until", "")
                .post("/overrides/edit")
                .then()
                .statusCode(404)
                .body(
                        "html.body.main.p.find { it.@id == 'notice' }",
                        is("There is no keep override with id gone-soon"
                                + " any more: it was deleted, or its id was changed in the file."));
        user.form(page, "delete-0").post("/overrides/delete").then().statusCode(404);

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/overrides")
                .then()
                .body("id", contains("replaced"))
                .body("[0].note", is(""));
    }

    @Test
    void formsWithoutTheirCsrfTokenAreRejectedAndNothingChanges() throws IOException {
        String yaml = "overrides:\n  - id: kept\n    hashes: [3c3c17]\n";
        Files.writeString(file, yaml);
        Session user = Session.login();
        Response page = awaitPage(user, "kept");

        for (String path : List.of("/overrides", "/overrides/edit", "/overrides/delete")) {
            given().redirects()
                    .follow(false)
                    .cookie(user.cookie())
                    .cookie(CSRF_COOKIE, page.cookie(CSRF_COOKIE))
                    .formParam("id", "kept")
                    .formParam("hashes", "1a1a17")
                    .formParam("note", "forged")
                    .when()
                    .post(path)
                    .then()
                    .statusCode(400);
        }
        given().redirects()
                .follow(false)
                .cookie(user.cookie())
                .cookie(CSRF_COOKIE, page.cookie(CSRF_COOKIE))
                .formParam("hashes", "1a1a17")
                .when()
                .post("/groups/keep")
                .then()
                .statusCode(400);

        assertEquals(yaml, Files.readString(file));
    }

    @Test
    void theApiCannotChangeOverrides() throws IOException {
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            Authenticated.withApiKey()
                    .contentType("application/json")
                    .body("{\"hashes\": [\"3c3c17\"], \"note\": \"via the API\"}")
                    .when()
                    .request(method, "/api/v1/overrides")
                    .then()
                    .statusCode(405);
        }
        for (String path : List.of("/api/v1/overrides/edit", "/api/v1/overrides/delete", "/api/v1/groups/keep")) {
            Authenticated.withApiKey()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("id", "x")
                    .formParam("hashes", "3c3c17")
                    .when()
                    .post(path)
                    .then()
                    .statusCode(anyOf(is(404), is(405)));
        }

        assertFalse(Files.exists(file), "the overrides file was written");
    }

    @Test
    void anOverrideCanBeAddedWhileTransmissionCannotBeUsed() {
        FakeTransmissionResource.fake().behaviour(FakeTransmission.Behaviour.RPC_ERROR);
        Session user = Session.login();
        Response page = user.page("/overrides");

        user.form(page, "add")
                .formParam("hashes", "3c3c17")
                .post("/overrides")
                .then()
                .statusCode(303);

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/overrides")
                .then()
                .statusCode(200)
                .body("hashes", contains(List.of("3c3c17")));
    }

    /** The value of the hidden input with this name in the form with this id. */
    private static String hidden(String form, String name) {
        return "**.find { it.name() == 'form' && it.@id == '" + form
                + "' }.input.find { it.@type == 'hidden' && it.@name == '" + name + "' }.@value";
    }

    /** Waits until the Overrides view shows the overrides with these ids: the file watcher may take a few seconds. */
    private static Response awaitPage(Session user, String... ids) {
        long deadline = System.nanoTime() + 60_000_000_000L;
        Response page;
        do {
            page = user.page("/overrides");
            if (page.htmlPath()
                    .getList(ROWS + ".collect { it.td[6].text() }", String.class)
                    .equals(List.of(ids))) {
                return page;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError(
                "The Overrides view never showed the overrides " + List.of(ids) + ": " + page.asString());
    }

    /** A logged-in user, who reads pages and posts their forms. */
    private record Session(Cookie cookie) {

        static Session login() {
            return new Session(Authenticated.login(TestSecrets.USERNAME, TestSecrets.PASSWORD));
        }

        Response page(String path) {
            return given().cookie(cookie).when().get(path);
        }

        /**
         * The form with this id of the page, filled in with its hidden fields (the CSRF token among them) and ready
         * to post.
         */
        RequestSpecification form(Response page, String id) {
            String form = "**.find { it.name() == 'form' && it.@id == '" + id + "' }";
            List<String> names = page.htmlPath().getList(form + ".input.findAll { it.@type == 'hidden' }.@name");
            List<String> values = page.htmlPath().getList(form + ".input.findAll { it.@type == 'hidden' }.@value");
            assertTrue(names.contains("csrf-token"), "the form " + id + " has no CSRF token");
            RequestSpecification request =
                    given().redirects().follow(false).cookie(cookie).cookie(CSRF_COOKIE, page.cookie(CSRF_COOKIE));
            for (int i = 0; i < names.size(); i++) {
                request.formParam(names.get(i), values.get(i));
            }
            return request;
        }
    }

    /** A finished, seeding torrent of tracker-a that has met its minimum: due for removal. */
    private static String torrent(String hash, String name, Path downloadDir, String file) {
        return """
                {"hash_string": "%s", "name": "%s", "status": 6, "percent_done": 1, "error": 0, "error_string": "",
                 "seconds_seeding": %d, "is_private": true, "download_dir": "%s",
                 "trackers": [{"announce": "https://tracker-a.example/announce"}],
                 "files": [{"name": "%s", "length": 4}], "file_stats": [{"wanted": true}]}
                """.formatted(hash, name, 400 * DAY, downloadDir, file);
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }
}
