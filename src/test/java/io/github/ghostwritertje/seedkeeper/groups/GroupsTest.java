package io.github.ghostwritertje.seedkeeper.groups;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmission.Behaviour;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Groups view and {@code GET /api/v1/groups}, end to end: torrents from the fake Transmission, files on disk, the
 * trackers of {@link FakeTransmissionResource}.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class GroupsTest {

    private static final long DAY = 86_400;

    @BeforeEach
    void torrents() throws IOException {
        Path downloads = FakeTransmissionResource.downloads();
        Path library = FakeTransmissionResource.library();
        Path episode = file(downloads.resolve("Linked.Show/e01.mkv"));
        file(downloads.resolve("Linked.Show/e02.mkv"));
        Path sample = file(downloads.resolve("Old.Movie/sample.mkv"));
        file(downloads.resolve("Old.Movie/movie.mkv"));
        file(downloads.resolve("Fresh.Movie/movie.mkv"));
        file(downloads.resolve("Unknown.Tracker/movie.mkv"));
        file(downloads.resolve("ubuntu.iso"));
        hardlink(library.resolve("tv/Linked Show/e01.mkv"), episode);
        hardlink(library.resolve("movies/sample.mkv"), sample);

        FakeTransmissionResource.fake().reset();
        FakeTransmissionResource.fake().torrents("[" + String.join(",",
                torrent("1111", "Linked.Show", 6, 1, 40 * DAY, true, "https://tracker-a.example/key/announce",
                        "Linked.Show/e01.mkv:true", "Linked.Show/e02.mkv:true"),
                torrent("2222", "Fresh.Movie", 6, 1, 2 * DAY + 12 * 3600, true,
                        "https://tracker-b.example.org/announce", "Fresh.Movie/movie.mkv:true"),
                torrent("3333", "Old.Movie", 0, 1, 14 * DAY, true, "https://tracker-b.example/announce",
                        "Old.Movie/movie.mkv:true", "Old.Movie/sample.mkv:false"),
                torrent("4444", "Unknown.Tracker", 6, 1, 400 * DAY, true, "https://elsewhere.example/announce",
                        "Unknown.Tracker/movie.mkv:true"),
                torrent("5555", "Downloading", 4, 0.5, 0, true, "https://tracker-a.example/key/announce",
                        "Downloading/movie.mkv:true"),
                torrent("6666", "ubuntu.iso", 6, 1, DAY, false, "udp://open.example:1337/announce",
                        "ubuntu.iso:true")) + "]");
    }

    @Test
    void apiGivesEveryTorrentAsAGroupWithItsVerdictAndReason() {
        given()
                .when().get("/api/v1/groups")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("verdict", contains("keep", "keep", "remove", "left-alone", "keep", "remove"))
                .body("reason", contains("library-link", "minimum-not-reached", "all-met", "unmanaged",
                        "not-finished", "all-met"))
                .body("libraryLinks", contains(1, 0, 0, 0, 0, 0))
                .body("files", contains(2, 1, 1, 1, 1, 1))
                .body("members.collect { it.size() }", contains(1, 1, 1, 1, 1, 1))
                .body("members.flatten().hash", contains("1111", "2222", "3333", "4444", "5555", "6666"))
                .body("members.flatten().name", contains("Linked.Show", "Fresh.Movie", "Old.Movie", "Unknown.Tracker",
                        "Downloading", "ubuntu.iso"))
                .body("members.flatten().tracker", contains("tracker-a", "tracker-b", "tracker-b", null, "tracker-a",
                        "public"))
                .body("members.flatten().status", contains("seeding", "seeding", "stopped", "seeding", "downloading",
                        "seeding"))
                .body("members.flatten().seedTimeSeconds", contains(3_456_000, 216_000, 1_209_600, 34_560_000, 0, 86_400))
                .body("members.flatten().minimumSeedTimeSeconds", contains(2_592_000, 1_209_600, 1_209_600, null,
                        2_592_000, 86_400));
    }

    @Test
    void apiGivesAnEmptyListWhenThereAreNoTorrents() {
        FakeTransmissionResource.fake().torrents("[]");

        given().when().get("/api/v1/groups").then().statusCode(200).body("$", empty());
    }

    @Test
    void apiGivesTheReasonWhenTransmissionCannotBeUsed() {
        FakeTransmissionResource.fake().version("4.0.6 (38c164933e)");

        given()
                .when().get("/api/v1/groups")
                .then()
                .statusCode(503)
                .contentType("application/json")
                .body("reason", is("Transmission 4.0.6 (38c164933e) is older than 4.1,"
                        + " the oldest version seedkeeper works with"));
    }

    @Test
    void pageShowsEveryTorrentWithItsVerdictAndReason() {
        given()
                .when().get("/groups")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body("html.head.title", is("Groups · seedkeeper"))
                .body("html.body.main.table.thead.tr.th", contains("Name", "Tracker", "Seed time / minimum",
                        "Status", "Library links", "Verdict", "Reason"))
                .body("html.body.main.table.tbody.tr.size()", is(6))
                .body("html.body.main.table.tbody.tr[0].td", contains("Linked.Show", "tracker-a", "40d / 30d",
                        "Seeding", "1 of 2 files", "Keep", "Library link (1 of 2 files)"))
                .body("html.body.main.table.tbody.tr[1].td", contains("Fresh.Movie", "tracker-b", "2d 12h / 14d",
                        "Seeding", "0 of 1 files", "Keep", "Minimum not reached"))
                .body("html.body.main.table.tbody.tr[2].td", contains("Old.Movie", "tracker-b", "14d / 14d",
                        "Stopped", "0 of 1 files", "Remove", "All met"))
                .body("html.body.main.table.tbody.tr[3].td", contains("Unknown.Tracker", "unmanaged", "400d",
                        "Seeding", "0 of 1 files", "Left alone", "Unmanaged"))
                .body("html.body.main.table.tbody.tr[4].td", contains("Downloading", "tracker-a", "0m / 30d",
                        "Downloading", "0 of 1 files", "Keep", "Not finished"))
                .body("html.body.main.table.tbody.tr[5].td", contains("ubuntu.iso", "public", "1d / 1d",
                        "Seeding", "0 of 1 files", "Remove", "All met"));
    }

    @Test
    void pageGivesTheReasonWhenTransmissionCannotBeUsed() {
        FakeTransmissionResource.fake().behaviour(Behaviour.RPC_ERROR);

        given()
                .when().get("/groups")
                .then()
                .statusCode(200)
                .body("html.body.main.p.find { it.@role == 'alert' }",
                        containsString("refused session_get: Method not found"))
                .body("html.body.main.table.size()", is(0));
    }

    @Test
    void theNavigationLeadsToTheGroupsView() {
        given().when().get("/system").then()
                .body("html.body.header.nav.a.find { it.@href == '/groups' }", is("Groups"));
    }

    /** One torrent in Transmission's field names. Each file is {@code name:wanted}. */
    private static String torrent(String hash, String name, int status, double percentDone, long secondsSeeding,
            boolean isPrivate, String announce, String... files) {
        StringBuilder fileList = new StringBuilder();
        StringBuilder fileStats = new StringBuilder();
        for (String file : files) {
            String[] parts = file.split(":");
            fileList.append(fileList.isEmpty() ? "" : ",")
                    .append("{\"name\":\"").append(parts[0]).append("\",\"length\":4}");
            fileStats.append(fileStats.isEmpty() ? "" : ",").append("{\"wanted\":").append(parts[1]).append("}");
        }
        return """
                {"hash_string": "%s", "name": "%s", "status": %d, "percent_done": %s, "error": 0, "error_string": "",
                 "seconds_seeding": %d, "is_private": %s, "download_dir": "%s",
                 "trackers": [{"announce": "%s"}], "files": [%s], "file_stats": [%s]}
                """.formatted(hash, name, status, percentDone, secondsSeeding, isPrivate,
                FakeTransmissionResource.downloads(), announce, fileList, fileStats);
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }

    private static void hardlink(Path link, Path existing) throws IOException {
        if (Files.notExists(link)) {
            Files.createDirectories(link.getParent());
            Files.createLink(link, existing);
        }
    }
}
