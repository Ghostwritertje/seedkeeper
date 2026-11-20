package io.github.ghostwritertje.seedkeeper.groups;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.ScrollRegions;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmission.Behaviour;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
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
        Path original = file(downloads.resolve("Cross.Seed/movie.mkv"));
        symlink(downloads.resolve("Cross.Seed.Elsewhere/movie.mkv"), original);
        file(downloads.resolve("Paused.Movie/movie.mkv"));
        hardlink(library.resolve("tv/Linked Show/e01.mkv"), episode);
        hardlink(library.resolve("movies/sample.mkv"), sample);

        FakeTransmissionResource.fake().reset();
        FakeTransmissionResource.fake()
                .torrents("["
                        + String.join(
                                ",",
                                torrent(
                                        "1111",
                                        "Linked.Show",
                                        6,
                                        1,
                                        40 * DAY,
                                        true,
                                        "https://tracker-a.example/key/announce",
                                        "Linked.Show/e01.mkv:true",
                                        "Linked.Show/e02.mkv:true"),
                                torrent(
                                        "2222",
                                        "Fresh.Movie",
                                        6,
                                        1,
                                        2 * DAY + 12 * 3600,
                                        true,
                                        "https://tracker-b.example.org/announce",
                                        "Fresh.Movie/movie.mkv:true"),
                                torrent(
                                        "3333",
                                        "Old.Movie",
                                        0,
                                        1,
                                        14 * DAY,
                                        true,
                                        "https://tracker-b.example/announce",
                                        "Old.Movie/movie.mkv:true",
                                        "Old.Movie/sample.mkv:false"),
                                torrent(
                                        "4444",
                                        "Unknown.Tracker",
                                        6,
                                        1,
                                        400 * DAY,
                                        true,
                                        "https://elsewhere.example/announce",
                                        "Unknown.Tracker/movie.mkv:true"),
                                torrent(
                                        "5555",
                                        "Downloading",
                                        4,
                                        0.5,
                                        0,
                                        true,
                                        "https://tracker-a.example/key/announce",
                                        "Downloading/movie.mkv:true"),
                                torrent(
                                        "6666",
                                        "ubuntu.iso",
                                        6,
                                        1,
                                        DAY,
                                        false,
                                        "udp://open.example:1337/announce",
                                        "ubuntu.iso:true"),
                                // A cross-seed group: 8888 is a symlink to the data of 7777. Paused.Movie sits between
                                // them.
                                torrent(
                                        "7777",
                                        "Cross.Seed",
                                        6,
                                        1,
                                        20 * DAY,
                                        true,
                                        "https://tracker-a.example/key/announce",
                                        "Cross.Seed/movie.mkv:true"),
                                torrent(
                                        "9999",
                                        "Paused.Movie",
                                        0,
                                        1,
                                        29 * DAY,
                                        true,
                                        "https://tracker-a.example/key/announce",
                                        "Paused.Movie/movie.mkv:true"),
                                torrent(
                                        "8888",
                                        "Cross.Seed.Elsewhere",
                                        0,
                                        1,
                                        13 * DAY,
                                        true,
                                        "https://tracker-b.example/announce",
                                        "Cross.Seed.Elsewhere/movie.mkv:true"))
                        + "]");
    }

    @Test
    void apiGivesEveryGroupWithItsMembersVerdictAndReason() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("verdict", contains("keep", "keep", "remove", "left-alone", "keep", "remove", "keep", "keep"))
                .body(
                        "reason",
                        contains(
                                "library-link",
                                "minimum-not-reached",
                                "all-met",
                                "unmanaged",
                                "not-finished",
                                "all-met",
                                "minimum-not-reached",
                                "minimum-not-reached"))
                .body("libraryLinks", contains(1, 0, 0, 0, 0, 0, 0, 0))
                .body("files", contains(2, 1, 1, 1, 1, 1, 1, 1))
                .body("members.collect { it.size() }", contains(1, 1, 1, 1, 1, 1, 2, 1))
                .body(
                        "members.flatten().hash",
                        contains("1111", "2222", "3333", "4444", "5555", "6666", "7777", "8888", "9999"))
                .body(
                        "members.flatten().name",
                        contains(
                                "Linked.Show",
                                "Fresh.Movie",
                                "Old.Movie",
                                "Unknown.Tracker",
                                "Downloading",
                                "ubuntu.iso",
                                "Cross.Seed",
                                "Cross.Seed.Elsewhere",
                                "Paused.Movie"))
                .body(
                        "members.flatten().tracker",
                        contains(
                                "tracker-a",
                                "tracker-b",
                                "tracker-b",
                                null,
                                "tracker-a",
                                "public",
                                "tracker-a",
                                "tracker-b",
                                "tracker-a"))
                .body(
                        "members.flatten().status",
                        contains(
                                "seeding",
                                "seeding",
                                "stopped",
                                "seeding",
                                "downloading",
                                "seeding",
                                "seeding",
                                "stopped",
                                "stopped"))
                .body(
                        "members.flatten().seedTimeSeconds",
                        contains(3_456_000, 216_000, 1_209_600, 34_560_000, 0, 86_400, 1_728_000, 1_123_200, 2_505_600))
                .body(
                        "members.flatten().minimumSeedTimeSeconds",
                        contains(
                                2_592_000, 1_209_600, 1_209_600, null, 2_592_000, 86_400, 2_592_000, 1_209_600,
                                2_592_000));
    }

    @Test
    void apiGivesTheForecastOfAGroupKeptForItsMinimum() {
        Instant before = Instant.now();

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .statusCode(200)
                .body(
                        "forecast",
                        contains(
                                nullValue(),
                                notNullValue(),
                                nullValue(),
                                nullValue(),
                                nullValue(),
                                nullValue(),
                                notNullValue(),
                                notNullValue()))
                // Fresh.Movie: 2d 12h of 14d.
                .body("[1].forecast.clockStopped", is(false))
                .body("[1].forecast.date", dateIn(before, Duration.ofDays(11).plusHours(12)))
                // The cross-seed group: the seeding member has 10 days to go, the paused one only 1.
                .body("[6].forecast.clockStopped", is(false))
                .body("[6].forecast.date", dateIn(before, Duration.ofDays(10)))
                // Paused.Movie: 1 day to go, and paused.
                .body("[7].forecast.clockStopped", is(true))
                .body("[7].forecast.date", nullValue());
    }

    @Test
    void apiGivesAnEmptyListWhenThereAreNoTorrents() {
        FakeTransmissionResource.fake().torrents("[]");

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void apiGivesTheReasonWhenTransmissionCannotBeUsed() {
        FakeTransmissionResource.fake().version("4.0.6 (38c164933e)");

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/groups")
                .then()
                .statusCode(503)
                .contentType("application/json")
                .body(
                        "reason",
                        is("Transmission 4.0.6 (38c164933e) is older than 4.1,"
                                + " the oldest version seedkeeper works with"));
    }

    @Test
    void pageShowsEveryGroupWithItsMembersVerdictReasonAndForecast() {
        Instant before = Instant.now();

        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body("html.head.title", is("Groups · seedkeeper"))
                .body(ScrollRegions.TABLE_PARENTS, ScrollRegions.regions("Groups"))
                .body(
                        "html.body.main.div.table.thead.tr.th",
                        contains(
                                "Name",
                                "Tracker",
                                "Seed time / minimum",
                                "Status",
                                "Tracker message",
                                "Library links",
                                "Verdict",
                                "Reason",
                                "Secondary reasons",
                                "Forecast",
                                "Keep override"))
                .body("html.body.main.div.table.tbody.tr.size()", is(9))
                .body(
                        cells(0),
                        contains(
                                "Linked.Show",
                                "tracker-a",
                                "40d / 30d",
                                "Seeding",
                                "",
                                "1 of 2 files",
                                "Keep",
                                "Library link (1 of 2 files)",
                                "",
                                ""))
                .body(
                        cells(1),
                        contains(
                                is("Fresh.Movie"),
                                is("tracker-b"),
                                is("2d 12h / 14d"),
                                is("Seeding"),
                                is(""),
                                is("0 of 1 files"),
                                is("Keep"),
                                is("Minimum not reached"),
                                is(""),
                                minuteIn(before, Duration.ofDays(11).plusHours(12))))
                .body(
                        cells(2),
                        contains(
                                "Old.Movie",
                                "tracker-b",
                                "14d / 14d",
                                "Stopped",
                                "",
                                "0 of 1 files",
                                "Remove",
                                "All met",
                                "",
                                ""))
                .body(
                        cells(3),
                        contains(
                                "Unknown.Tracker",
                                "unmanaged",
                                "400d",
                                "Seeding",
                                "",
                                "0 of 1 files",
                                "Left alone",
                                "Unmanaged",
                                "",
                                ""))
                .body(
                        cells(4),
                        contains(
                                "Downloading",
                                "tracker-a",
                                "0m / 30d",
                                "Downloading",
                                "",
                                "0 of 1 files",
                                "Keep",
                                "Not finished",
                                "Minimum not reached",
                                ""))
                .body(
                        cells(5),
                        contains(
                                "ubuntu.iso",
                                "public",
                                "1d / 1d",
                                "Seeding",
                                "",
                                "0 of 1 files",
                                "Remove",
                                "All met",
                                "",
                                ""))
                // The cross-seed group: one row per member, the group's cells span both.
                .body(
                        cells(6),
                        contains(
                                is("Cross.Seed"),
                                is("tracker-a"),
                                is("20d / 30d"),
                                is("Seeding"),
                                is(""),
                                is("0 of 1 files"),
                                is("Keep"),
                                is("Minimum not reached"),
                                is(""),
                                minuteIn(before, Duration.ofDays(10))))
                .body(
                        "html.body.main.div.table.tbody.tr[6].td.collect { it.@rowspan.toString() }",
                        contains("1", "1", "1", "1", "1", "2", "2", "2", "2", "2", "2"))
                // The keep form's controls name the group by its first member, so they can be told apart.
                .body(
                        "**.find { it.name() == 'form' && it.@id == 'keep-7777' }.'**'.findAll { it.@'aria-label' != '' }"
                                + ".collect { it.@'aria-label'.toString() }",
                        contains("Note for Cross.Seed", "Until for Cross.Seed", "Set a keep override for Cross.Seed"))
                .body(cells(7), contains("Cross.Seed.Elsewhere", "tracker-b", "13d / 14d", "Stopped", ""))
                .body(
                        cells(8),
                        contains(
                                "Paused.Movie",
                                "tracker-a",
                                "29d / 30d",
                                "Stopped",
                                "",
                                "0 of 1 files",
                                "Keep",
                                "Minimum not reached",
                                "",
                                "clock stopped (paused)"));
    }

    @Test
    void pageGivesTheReasonWhenTransmissionCannotBeUsed() {
        FakeTransmissionResource.fake().behaviour(Behaviour.RPC_ERROR);

        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(
                        "html.body.main.p.find { it.@role == 'alert' }",
                        containsString("refused session_get: Method not found"))
                .body("html.body.main.'**'.findAll { it.name() == 'table' }.size()", is(0));
    }

    @Test
    void theNavigationLeadsToTheGroupsView() {
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .body("html.body.header.nav.a.find { it.@href == '/groups' }", is("Groups"));
    }

    @Test
    void theNavigationMarksTheViewThatIsShown() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .body("html.body.header.nav.a.findAll { it.@'aria-current' == 'page' }.@href", is("/groups"))
                .body("html.body.main.@data-page", is("groups"));
    }

    /** One torrent in Transmission's field names. Each file is {@code name:wanted}. */
    private static String torrent(
            String hash,
            String name,
            int status,
            double percentDone,
            long secondsSeeding,
            boolean isPrivate,
            String announce,
            String... files) {
        StringBuilder fileList = new StringBuilder();
        StringBuilder fileStats = new StringBuilder();
        for (String file : files) {
            String[] parts = file.split(":", 2);
            fileList.append(fileList.isEmpty() ? "" : ",")
                    .append("{\"name\":\"")
                    .append(parts[0])
                    .append("\",\"length\":4}");
            fileStats
                    .append(fileStats.isEmpty() ? "" : ",")
                    .append("{\"wanted\":")
                    .append(parts[1])
                    .append("}");
        }
        return """
                {"hash_string": "%s", "name": "%s", "status": %d, "percent_done": %s, "error": 0, "error_string": "",
                 "seconds_seeding": %d, "is_private": %s, "download_dir": "%s",
                 "trackers": [{"announce": "%s"}], "files": [%s], "file_stats": [%s]}
                """.formatted(
                        hash,
                        name,
                        status,
                        percentDone,
                        secondsSeeding,
                        isPrivate,
                        FakeTransmissionResource.downloads(),
                        announce,
                        fileList,
                        fileStats);
    }

    /** The text of every cell of a row of the table, up to the form that sets a keep override. */
    private static String cells(int row) {
        return "html.body.main.div.table.tbody.tr[" + row + "].td.take(10).collect { it.text() }";
    }

    /**
     * An ISO date with seconds, in the application's time zone, that is {@code remaining} after the moment the
     * application decided: somewhere between {@code before} and now.
     */
    private static Matcher<String> dateIn(Instant before, Duration remaining) {
        return new TypeSafeMatcher<>() {
            @Override
            protected boolean matchesSafely(String text) {
                OffsetDateTime date = OffsetDateTime.parse(text);
                Instant earliest = before.truncatedTo(ChronoUnit.SECONDS).plus(remaining);
                Instant latest = Instant.now().plus(remaining);
                return date.getOffset().equals(ZoneOffset.ofHoursMinutes(5, 30))
                        && !date.toInstant().isBefore(earliest)
                        && !date.toInstant().isAfter(latest);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("a date at +05:30, " + remaining + " after a moment since " + before);
            }
        };
    }

    /** The same moment as the page writes it: to the minute, in the application's time zone. */
    private static Matcher<String> minuteIn(Instant before, Duration remaining) {
        DateTimeFormatter format =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(FakeTransmissionResource.TIME_ZONE);
        return new TypeSafeMatcher<>() {
            @Override
            protected boolean matchesSafely(String text) {
                return text.equals(format.format(before.plus(remaining)))
                        || text.equals(format.format(Instant.now().plus(remaining)));
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("the minute " + format.format(before.plus(remaining)) + " or the next");
            }
        };
    }

    private static void symlink(Path link, Path target) throws IOException {
        if (Files.notExists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, target);
        }
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
