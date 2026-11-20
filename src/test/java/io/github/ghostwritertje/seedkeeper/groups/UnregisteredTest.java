package io.github.ghostwritertje.seedkeeper.groups;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unregistered torrents, tracker errors and the tracker message in the Groups view and {@code GET /api/v1/groups},
 * end to end.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class UnregisteredTest {

    private static final long DAY = 86_400;
    private static final int CHECKING = 2;
    private static final int SEEDING = 6;
    private static final int NO_ERROR = 0;
    private static final int TRACKER_WARNING = 1;
    private static final int TRACKER_ERROR = 2;
    private static final String TRACKER_A = "https://tracker-a.example/key/announce";
    private static final String TRACKER_B = "https://tracker-b.example/announce";

    @BeforeEach
    void torrents() throws IOException {
        Path downloads = FakeTransmissionResource.downloads();
        Path library = FakeTransmissionResource.library();
        Path gone = file(downloads.resolve("Gone.Movie/movie.mkv"));
        if (Files.notExists(library.resolve("movies/Gone Movie.mkv"))) {
            Files.createDirectories(library.resolve("movies"));
            Files.createLink(library.resolve("movies/Gone Movie.mkv"), gone);
        }
        Path original = file(downloads.resolve("Cross.Seed/movie.mkv"));
        Path link = downloads.resolve("Cross.Seed.Elsewhere/movie.mkv");
        if (Files.notExists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, original);
        }
        file(downloads.resolve("Passkey.Movie/movie.mkv"));
        file(downloads.resolve("Warned.Movie/movie.mkv"));
        file(downloads.resolve("Checking.Gone/movie.mkv"));

        FakeTransmissionResource.fake().reset();
        FakeTransmissionResource.fake()
                .torrents("["
                        + String.join(
                                ",",
                                // Unregistered, with a library link and far from its minimum.
                                torrent(
                                        "1111",
                                        "Gone.Movie",
                                        SEEDING,
                                        TRACKER_ERROR,
                                        "Unregistered torrent",
                                        DAY,
                                        TRACKER_A),
                                // A cross-seed group whose second member is unregistered.
                                torrent("2222", "Cross.Seed", SEEDING, NO_ERROR, "", 400 * DAY, TRACKER_A),
                                torrent(
                                        "3333",
                                        "Cross.Seed.Elsewhere",
                                        SEEDING,
                                        TRACKER_ERROR,
                                        "Torrent not registered with this tracker.",
                                        2 * DAY,
                                        TRACKER_B),
                                torrent(
                                        "4444",
                                        "Passkey.Movie",
                                        SEEDING,
                                        TRACKER_ERROR,
                                        "Passkey does not exist!",
                                        400 * DAY,
                                        TRACKER_A),
                                torrent(
                                        "5555",
                                        "Warned.Movie",
                                        SEEDING,
                                        TRACKER_WARNING,
                                        "Slow down",
                                        400 * DAY,
                                        TRACKER_A),
                                torrent(
                                        "6666",
                                        "Checking.Gone",
                                        CHECKING,
                                        TRACKER_ERROR,
                                        "Unregistered torrent",
                                        400 * DAY,
                                        TRACKER_A))
                        + "]");
    }

    @Test
    void apiGivesAnUnregisteredTorrentAVerdictOfItsOwnAndJudgesTheRestOfItsGroupWithoutIt() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .statusCode(200)
                .body(
                        "members.collect { it.hash }",
                        contains(
                                contains("1111"),
                                contains("2222"),
                                contains("3333"),
                                contains("4444"),
                                contains("5555"),
                                contains("6666")))
                .body("verdict", contains("remove", "remove", "remove", "remove", "remove", "keep"))
                .body("reason", contains("unregistered", "all-met", "unregistered", "all-met", "all-met", "verifying"))
                .body("[0].secondaryReasons", contains("library-link", "minimum-not-reached"))
                .body("[2].secondaryReasons", contains("minimum-not-reached"));
    }

    @Test
    void apiGivesWhatTheTrackerReportsForEachMember() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .statusCode(200)
                .body(
                        "members.flatten().trackerError",
                        contains(
                                "unregistered",
                                null,
                                "unregistered",
                                "tracker-error",
                                "tracker-warning",
                                "unregistered"))
                .body(
                        "members.flatten().trackerMessage",
                        contains(
                                "Unregistered torrent",
                                null,
                                "Torrent not registered with this tracker.",
                                "Passkey does not exist!",
                                "Slow down",
                                "Unregistered torrent"));
    }

    @Test
    void apiFiltersOnUnregistered() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups?reason=unregistered")
                .then()
                .statusCode(200)
                .body("members.flatten().hash", contains("1111", "3333"));
    }

    @Test
    void pageShowsTheTrackerMessageOfEachMember() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                // No group here has a forecast, so there is no Forecast column.
                .body(GroupsTable.HEADINGS, contains("Name", "Seed time / minimum", "Verdict", "Reason", "Keep override"))
                .body(
                        GroupsTable.heading(0),
                        contains(
                                "Gone.Movie",
                                "tracker-a · Seeding · 1 of 1 files linked",
                                "Unregistered: Unregistered torrent"))
                .body(GroupsTable.cells(0), contains("1d / 30d", "Remove", "Unregistered"))
                .body(GroupsTable.underReason(0), is("also: Library link, Minimum not reached"))
                // No tracker message, no line for it.
                .body(GroupsTable.heading(1), contains("Cross.Seed", "tracker-a · Seeding · 0 of 1 files linked"))
                .body(GroupsTable.cells(1), contains("400d / 30d", "Remove", "All met"))
                .body(
                        GroupsTable.heading(2),
                        contains(
                                "Cross.Seed.Elsewhere",
                                "tracker-b · Seeding · 0 of 1 files linked",
                                "Unregistered: Torrent not registered with this tracker."))
                .body(GroupsTable.cells(2), contains("2d / 14d", "Remove", "Unregistered"))
                .body(GroupsTable.underReason(2), is("also: Minimum not reached"))
                .body(
                        GroupsTable.heading(3),
                        contains(
                                "Passkey.Movie",
                                "tracker-a · Seeding · 0 of 1 files linked",
                                "Tracker error: Passkey does not exist!"))
                .body(GroupsTable.cells(3), contains("400d / 30d", "Remove", "All met"))
                .body(
                        GroupsTable.heading(4),
                        contains(
                                "Warned.Movie",
                                "tracker-a · Seeding · 0 of 1 files linked",
                                "Tracker warning: Slow down"))
                .body(
                        GroupsTable.heading(5),
                        contains(
                                "Checking.Gone",
                                "tracker-a · Verifying · 0 of 1 files linked",
                                "Unregistered: Unregistered torrent"))
                .body(GroupsTable.cells(5), contains("400d / 30d", "Keep", "Verifying"));
    }

    @Test
    void pageShowsTheTrackerMessageUnderTheMemberThatHasIt() {
        FakeTransmissionResource.fake()
                .torrents("["
                        + torrent("2222", "Cross.Seed", SEEDING, NO_ERROR, "", 400 * DAY, TRACKER_A)
                        + ","
                        + torrent("3333", "Cross.Seed.Elsewhere", SEEDING, TRACKER_WARNING, "Slow down", 2 * DAY, TRACKER_B)
                        + "]");

        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(GroupsTable.heading(0), contains("Cross.Seed", "2 torrents · 0 of 1 files linked"))
                .body(GroupsTable.member(0, 0), contains("Cross.Seed", "tracker-a · Seeding", "400d / 30d"))
                .body(
                        GroupsTable.member(0, 1),
                        contains("Cross.Seed.Elsewhere", "tracker-b · Seeding", "Tracker warning: Slow down", "2d / 14d"));
    }

    @Test
    void pageFiltersOnUnregistered() {
        Authenticated.asUser()
                .when()
                .get("/groups?reason=unregistered")
                .then()
                .statusCode(200)
                .body("html.body.main.div.table.tbody.tr.size()", is(2))
                .body(
                        "html.body.main.form.'**'.find { it.name() == 'select' && it.@name == 'reason' }"
                                + ".option.find { it.@selected == 'selected' }.text()",
                        is("Unregistered"));
    }

    /** One private, finished torrent with one wanted file, {@code <name>/movie.mkv}, in Transmission's field names. */
    private static String torrent(
            String hash, String name, int status, int error, String errorString, long secondsSeeding, String announce) {
        return """
                {"hash_string": "%s", "name": "%s", "status": %d, "percent_done": 1, "error": %d,
                 "error_string": "%s", "seconds_seeding": %d, "is_private": true, "download_dir": "%s",
                 "trackers": [{"announce": "%s"}], "files": [{"name": "%s/movie.mkv", "length": 4}],
                 "file_stats": [{"wanted": true}]}
                """.formatted(
                        hash,
                        name,
                        status,
                        error,
                        errorString,
                        secondsSeeding,
                        FakeTransmissionResource.downloads(),
                        announce,
                        name);
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }
}
