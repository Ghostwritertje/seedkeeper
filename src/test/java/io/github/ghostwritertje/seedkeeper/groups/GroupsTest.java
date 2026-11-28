package io.github.ghostwritertje.seedkeeper.groups;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
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

    /** The passkey a private tracker puts in the path or the query of an announce URL. */
    private static final String PASSKEY = "0f3c9a1e7b5d24c68e1a9f0b3d7c5e2a";

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
                // Every file is 4 bytes. Old.Movie's sample is not wanted, so it counts nowhere; the cross-seed
                // group's two members share their one file, so the group counts it once.
                .body("sizeBytes", contains(8, 4, 4, 4, 4, 4, 4, 4))
                .body("members.flatten().sizeBytes", contains(8, 4, 4, 4, 4, 4, 4, 4, 4))
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
                        "members.flatten().announceHosts",
                        contains(
                                List.of("tracker-a.example"),
                                List.of("tracker-b.example.org"),
                                List.of("tracker-b.example"),
                                List.of("elsewhere.example"),
                                List.of("tracker-a.example"),
                                List.of("open.example"),
                                List.of("tracker-a.example"),
                                List.of("tracker-b.example"),
                                List.of("tracker-a.example")))
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
                        GroupsTable.HEADINGS,
                        contains("Name", "Minimum seed time", "Verdict", "Reason", "Forecast", "Size", "Keep override"))
                // Remove first, then Left alone, then Keep, each by soonest forecast: a stopped clock after every
                // date, no forecast last.
                .body(
                        GroupsTable.NAMES,
                        contains(
                                "Old.Movie",
                                "ubuntu.iso",
                                "Unknown.Tracker",
                                "Cross.Seed",
                                "Fresh.Movie",
                                "Paused.Movie",
                                "Linked.Show",
                                "Downloading"))
                // One header row per group; member rows only under the cross-seed group of two.
                .body(GroupsTable.ROWS, contains(1, 1, 1, 3, 1, 1, 1, 1))
                .body(GroupsTable.heading(6), contains("Linked.Show", "tracker-a · Seeding · 1 of 2 files linked"))
                // The library links show once: under the name, not again in the reason. Every file is 4 bytes.
                .body(GroupsTable.cells(6), contains("Met ✓", "Keep", "Library link", "", "8 B"))
                .body(GroupsTable.progressDetail(6), is("40d of 30d · met 10d ago"))
                .body(GroupsTable.heading(4), contains("Fresh.Movie", "tracker-b · Seeding · 0 of 1 files linked"))
                .body(
                        GroupsTable.cells(4),
                        contains(
                                is("18%"),
                                is("Keep"),
                                is("Minimum not reached"),
                                minuteIn(before, Duration.ofDays(11).plusHours(12)),
                                is("4 B")))
                // The forecast is a <time>, in ISO with the offset, and the time zone in its tooltip.
                .body(
                        GroupsTable.GROUPS + "[4].tr[0].td[3].time.@datetime",
                        dateIn(before, Duration.ofDays(11).plusHours(12)))
                .body(
                        GroupsTable.GROUPS + "[4].tr[0].td[3].time.@title",
                        matchesPattern("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d Asia/Kolkata"))
                .body(GroupsTable.heading(0), contains("Old.Movie", "tracker-b · Stopped · 0 of 1 files linked"))
                // Below the minimum: a meter for screen readers, the time to go as detail.
                .body(GroupsTable.progressCell(4) + ".meter.@value", is("18"))
                .body(GroupsTable.progressDetail(4), is("2d 12h of 14d · 11d 12h to go"))
                // Its sample is not wanted: it counts nowhere.
                .body(GroupsTable.cells(0), contains("Met ✓", "Remove", "All met", "", "4 B"))
                .body(GroupsTable.progressDetail(0), is("14d of 14d · met just now"))
                .body(GroupsTable.progressCell(0) + ".meter.size()", is(0))
                .body(
                        GroupsTable.heading(2),
                        contains("Unknown.Tracker", "Unmanaged (elsewhere.example) · Seeding · 0 of 1 files linked"))
                .body(GroupsTable.cells(2), contains("—", "Left alone", "Unmanaged", "", "4 B"))
                // What to do about it, under the reason.
                .body(
                        GroupsTable.hint(2),
                        is("a member is on a tracker not in the config: add it under trackers to manage it"))
                .body(GroupsTable.progressDetail(2), is("400d · no minimum"))
                .body(GroupsTable.heading(7), contains("Downloading", "tracker-a · Downloading · 0 of 1 files linked"))
                .body(GroupsTable.cells(7), contains("0%", "Keep", "Not finished", "", "4 B"))
                .body(GroupsTable.underReason(7), is("also: Minimum not reached"))
                .body(GroupsTable.underReason(6), is(""))
                .body(GroupsTable.heading(1), contains("ubuntu.iso", "public · Seeding · 0 of 1 files linked"))
                .body(GroupsTable.cells(1), contains("Met ✓", "Remove", "All met", "", "4 B"))
                // The cross-seed group: a header row for the group, whose cells span the rows of its members.
                .body(GroupsTable.heading(3), contains("Cross.Seed", "2 torrents · 0 of 1 files linked"))
                // Its header shows its slowest member, the one that sets the forecast: 10 days to go.
                .body(
                        GroupsTable.cells(3),
                        contains(
                                is("67%"),
                                is("Keep"),
                                is("Minimum not reached"),
                                minuteIn(before, Duration.ofDays(10)),
                                // Its members share their one file: the group counts it once.
                                is("4 B")))
                .body(GroupsTable.progressDetail(3), is("slowest: 20d of 30d · 10d to go"))
                .body(
                        GroupsTable.GROUPS + "[3].tr[0].td.collect { it.@rowspan.toString() }",
                        contains("1", "3", "3", "3", "3", "3"))
                .body(GroupsTable.member(3, 0), contains("Cross.Seed", "tracker-a · Seeding · 4 B", "67%"))
                .body(GroupsTable.member(3, 1), contains("Cross.Seed.Elsewhere", "tracker-b · Stopped · 4 B", "93%"))
                // The keep form's controls name the group by its first member, so they can be told apart.
                .body(
                        "**.find { it.name() == 'form' && it.@id == 'keep-7777' }.'**'.findAll { it.@'aria-label' != '' }"
                                + ".collect { it.@'aria-label'.toString() }",
                        contains("Note for Cross.Seed", "Until for Cross.Seed", "Set a keep override for Cross.Seed"))
                .body(GroupsTable.heading(5), contains("Paused.Movie", "tracker-a · Stopped · 0 of 1 files linked"))
                .body(
                        GroupsTable.cells(5),
                        contains("97%", "Keep", "Minimum not reached", "clock stopped (paused)", "4 B"))
                // A stopped clock has no moment: no <time>.
                .body(GroupsTable.GROUPS + "[5].tr[0].td[3].time.size()", is(0));
    }

    @Test
    void pageShowsEachVerdictAndReasonUnderTheGroupName() {
        // Small screens hide the verdict and reason columns: the verdict stays in sight under the name.
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(GroupsTable.verdictUnderName(4), contains("keep", "Keep", "Minimum not reached"))
                .body(GroupsTable.verdictUnderName(0), contains("remove", "Remove", "All met"))
                .body(GroupsTable.verdictUnderName(2), contains("left-alone", "Left alone", "Unmanaged"))
                .body(GroupsTable.verdictUnderName(3), contains("keep", "Keep", "Minimum not reached"));
    }

    @Test
    void pageSaysTheSizeIsTheDataNotTheSpaceARemovalFrees() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(
                        "html.body.main.details.p.collect { it.text() }.join(' ')",
                        containsString("The size is the data on disk of the group's wanted files, a file its members"
                                + " share counted once. It is not the space a removal frees"));
    }

    @Test
    void pageLetsTheProgressDetailBeReachedByKeyboardAndScreenReaders() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                // Focusable, so the detail opens on focus and tap as well as on hover.
                .body(GroupsTable.progressCell(4) + ".@tabindex", is("0"))
                // The text says the state; the colour only repeats it.
                .body(GroupsTable.progressCell(4) + ".@data-tone", is("warn"))
                .body(
                        GroupsTable.progressCell(4) + ".meter.collect { [it.@min, it.@max] }.flatten()",
                        contains("0", "100"))
                .body(GroupsTable.progressCell(0) + ".@data-tone", is("ok"))
                .body(GroupsTable.progressCell(2) + ".@data-tone", is("muted"))
                // The whole cell, as a screen reader reads it: the state, then the exact durations.
                .body(GroupsTable.progressCell(4) + ".text()", is("18%2d 12h of 14d · 11d 12h to go"))
                .body(
                        GroupsTable.GROUPS + "[3].tr[2].td[1].with { [it.@tabindex.toString(), it.small.text()] }",
                        contains("0", "13d of 14d · 1d to go"));
    }

    @Test
    void pageMayBreakANameOnlyAfterADotOrADash() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(GroupsTable.GROUPS + "[1].tr[0].th.strong.wbr.size()", is(1))
                .body(GroupsTable.GROUPS + "[3].tr[0].th.strong.wbr.size()", is(1))
                .body(GroupsTable.GROUPS + "[3].tr[2].td[0].span.wbr.size()", is(2));
    }

    @Test
    void pageLeavesOutTheForecastWhenNoGroupHasOne() {
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=remove")
                .then()
                .statusCode(200)
                .body(
                        GroupsTable.HEADINGS,
                        contains("Name", "Minimum seed time", "Verdict", "Reason", "Size", "Keep override"))
                .body(GroupsTable.cells(0), contains("Met ✓", "Remove", "All met", "4 B"));
    }

    @Test
    void anUnmanagedTorrentShowsItsAnnounceHostsButNeverItsPasskey() {
        FakeTransmissionResource.fake()
                .torrents("["
                        + String.join(
                                ",",
                                torrent(
                                        "aaaa",
                                        "Two.Hosts",
                                        6,
                                        1,
                                        DAY,
                                        true,
                                        List.of(
                                                "https://Elsewhere.example/" + PASSKEY + "/announce",
                                                "https://tracker-a.example/announce?passkey=" + PASSKEY,
                                                "https://elsewhere.example/" + PASSKEY + "/announce"),
                                        "Unknown.Tracker/movie.mkv:true"),
                                torrent(
                                        "bbbb",
                                        "No.Trackers",
                                        6,
                                        1,
                                        DAY,
                                        true,
                                        List.of(),
                                        "Fresh.Movie/movie.mkv:true"))
                        + "]");
        List<String> logged = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(String.valueOf(record.getMessage()) + " " + Arrays.toString(record.getParameters()));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        try {
            String page = Authenticated.asUser()
                    .when()
                    .get("/groups")
                    .then()
                    .statusCode(200)
                    // Each is a group of one: its tracker shows in the line under the group's name.
                    .body(
                            GroupsTable.heading(0),
                            contains(
                                    "Two.Hosts",
                                    "Unmanaged (elsewhere.example, tracker-a.example) · Seeding · 0 of 1 files linked"))
                    .body(
                            GroupsTable.heading(1),
                            contains("No.Trackers", "Unmanaged (no trackers) · Seeding · 0 of 1 files linked"))
                    .extract()
                    .asString();
            String api = Authenticated.withApiKey()
                    .when()
                    .get("/api/v1/groups")
                    .then()
                    .statusCode(200)
                    .body("members.flatten().tracker", contains(nullValue(), nullValue()))
                    .body(
                            "members.flatten().announceHosts",
                            contains(List.of("elsewhere.example", "tracker-a.example"), List.of()))
                    .extract()
                    .asString();

            assertFalse(page.contains(PASSKEY), "the page shows the passkey");
            assertFalse(api.contains(PASSKEY), "the API gives the passkey");
            assertFalse(logged.stream().anyMatch(line -> line.contains(PASSKEY)), "the log has the passkey");
        } finally {
            root.removeHandler(handler);
        }
    }

    @Test
    void pageMarksTheMemberThatCausesTheReason() {
        // The cross-seed group of the fixture, its second member on a tracker that isn't in the config.
        FakeTransmissionResource.fake()
                .torrents("["
                        + String.join(
                                ",",
                                torrent(
                                        "7777",
                                        "Cross.Seed",
                                        6,
                                        1,
                                        40 * DAY,
                                        true,
                                        "https://tracker-a.example/key/announce",
                                        "Cross.Seed/movie.mkv:true"),
                                torrent(
                                        "8888",
                                        "Cross.Seed.Elsewhere",
                                        6,
                                        1,
                                        40 * DAY,
                                        true,
                                        "https://elsewhere.example/announce",
                                        "Cross.Seed.Elsewhere/movie.mkv:true"))
                        + "]");

        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(GroupsTable.cells(0), contains("Met ✓", "Left alone", "Unmanaged", "4 B"))
                .body(
                        GroupsTable.hint(0),
                        is("a member is on a tracker not in the config: add it under trackers to manage it"))
                .body(GroupsTable.member(0, 0), contains("Cross.Seed", "tracker-a · Seeding · 4 B", "Met ✓"))
                .body(
                        GroupsTable.member(0, 1),
                        contains(
                                "Cross.Seed.Elsewhere",
                                "causes \"Unmanaged\"",
                                "Unmanaged (elsewhere.example) · Seeding · 4 B",
                                "—"))
                .body(GroupsTable.GROUPS + "[0].tr[2].@data-cause", is("true"));
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
        return torrent(hash, name, status, percentDone, secondsSeeding, isPrivate, List.of(announce), files);
    }

    /** One torrent with any number of announce URLs, none included. */
    private static String torrent(
            String hash,
            String name,
            int status,
            double percentDone,
            long secondsSeeding,
            boolean isPrivate,
            List<String> announces,
            String... files) {
        String trackers = announces.stream()
                .map(announce -> "{\"announce\": \"" + announce + "\"}")
                .collect(Collectors.joining(","));
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
                 "trackers": [%s], "files": [%s], "file_stats": [%s]}
                """.formatted(
                        hash,
                        name,
                        status,
                        percentDone,
                        secondsSeeding,
                        isPrivate,
                        FakeTransmissionResource.downloads(),
                        trackers,
                        fileList,
                        fileStats);
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
