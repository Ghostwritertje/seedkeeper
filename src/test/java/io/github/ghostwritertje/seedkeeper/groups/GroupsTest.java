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
    void pageShowsALaneForEachVerdictKeepLeftAloneRemoveWithItsCountAndWhatItHolds() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body("html.head.title", is("Groups · seedkeeper"))
                // The lanes are no table: nothing scrolls sideways.
                .body(ScrollRegions.TABLE_PARENTS, is(""))
                // The heading says the verdict in words; the colour only repeats it.
                .body(GroupsLanes.HEADINGS, contains("5 Keep", "1 Left alone", "2 Remove"))
                .body(
                        GroupsLanes.LANES + ".collect { it.p[0].text() }",
                        contains("waiting, soonest first", "a person should look", "the next run removes these"))
                .body(GroupsLanes.LANES + ".@'data-verdict'", contains("keep", "left-alone", "remove"))
                .body(GroupsLanes.LANES + ".h2.mark.@'data-verdict'", contains("keep", "left-alone", "remove"))
                .body(GroupsLanes.LANES + ".collect { it.@'aria-labelledby'.text() }", contains("lane-keep", "lane-left-alone", "lane-remove"))
                // Keep by soonest forecast: a stopped clock after every date, no forecast last.
                .body(
                        GroupsLanes.names("keep"),
                        contains("Cross.Seed", "Fresh.Movie", "Paused.Movie", "Linked.Show", "Downloading"))
                .body(GroupsLanes.names("left-alone"), is("Unknown.Tracker"))
                // Remove largest first; the two are as large, so they keep the snapshot order.
                .body(GroupsLanes.names("remove"), contains("Old.Movie", "ubuntu.iso"))
                // Each card carries the verdict of its lane.
                .body(
                        GroupsLanes.CARDS + ".@'data-verdict'",
                        contains("keep", "keep", "keep", "keep", "keep", "left-alone", "remove", "remove"));
    }

    @Test
    void aCardShowsTheGroupItsReasonAndItsProgressWithTheForecastBelowTheMinimum() {
        Instant before = Instant.now();

        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                // The library links show once: in the summary, not again in the reason. Every file is 4 bytes.
                .body(GroupsLanes.summary("keep", 3), is("tracker-a · Seeding · 1 of 2 files linked · 8 B"))
                .body(GroupsLanes.reason("keep", 3), is("Library link"))
                .body(GroupsLanes.secondaryReasons("keep", 3), is(""))
                // Met: no progress to show.
                .body(GroupsLanes.progressLine("keep", 3) + ".size()", is(0))
                .body(GroupsLanes.summary("keep", 1), is("tracker-b · Seeding · 0 of 1 files linked · 4 B"))
                .body(GroupsLanes.reason("keep", 1), is("Minimum not reached"))
                // Below the minimum: a meter, the percent and when it becomes removable, and the exact durations.
                .body(
                        GroupsLanes.progress("keep", 1),
                        endsInMinute("18% of minimum · removable ", before, Duration.ofDays(11).plusHours(12)))
                .body(GroupsLanes.progressLine("keep", 1) + ".meter.@value", is("18"))
                .body(
                        GroupsLanes.progressLine("keep", 1) + ".meter.collect { [it.@min, it.@max] }.flatten()",
                        contains("0", "100"))
                .body(GroupsLanes.progressDetail("keep", 1), is("2d 12h of 14d · 11d 12h to go"))
                // The forecast is a <time>, in ISO with the offset, and the time zone in its tooltip.
                .body(
                        GroupsLanes.progressLine("keep", 1) + ".time.@datetime",
                        dateIn(before, Duration.ofDays(11).plusHours(12)))
                .body(
                        GroupsLanes.progressLine("keep", 1) + ".time.@title",
                        matchesPattern("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d Asia/Kolkata"))
                // Its sample is not wanted: it counts nowhere.
                .body(GroupsLanes.summary("remove", 0), is("tracker-b · Stopped · 0 of 1 files linked · 4 B"))
                .body(GroupsLanes.reason("remove", 0), is("All met"))
                .body(GroupsLanes.progressLine("remove", 0) + ".size()", is(0))
                .body(
                        GroupsLanes.summary("left-alone", 0),
                        is("Unmanaged (elsewhere.example) · Seeding · 0 of 1 files linked · 4 B"))
                .body(GroupsLanes.reason("left-alone", 0), is("Unmanaged"))
                // What to do about it, under the reason.
                .body(
                        GroupsLanes.hint("left-alone", 0),
                        is("a member is on a tracker not in the config: add it under trackers to manage it"))
                .body(GroupsLanes.hint("keep", 1), is(""))
                .body(GroupsLanes.summary("keep", 4), is("tracker-a · Downloading · 0 of 1 files linked · 4 B"))
                .body(GroupsLanes.reason("keep", 4), is("Not finished"))
                .body(GroupsLanes.secondaryReasons("keep", 4), is("also: Minimum not reached"))
                // No forecast: the progress alone.
                .body(GroupsLanes.progress("keep", 4), is("0% of minimum"))
                .body(GroupsLanes.summary("remove", 1), is("public · Seeding · 0 of 1 files linked · 4 B"))
                .body(GroupsLanes.summary("keep", 2), is("tracker-a · Stopped · 0 of 1 files linked · 4 B"))
                // A stopped clock has no moment: no <time>.
                .body(GroupsLanes.progress("keep", 2), is("97% of minimum · clock stopped (paused)"))
                .body(GroupsLanes.progressLine("keep", 2) + ".time.size()", is(0));
    }

    @Test
    void aCardOfACrossSeedGroupShowsItsSlowestMemberAndOpensItsMembersOnDemand() {
        Instant before = Instant.now();

        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                // Its members share their one file: the group counts it once.
                .body(GroupsLanes.summary("keep", 0), is("2 torrents · 0 of 1 files linked · 4 B"))
                // Its slowest member sets the forecast: 10 days to go.
                .body(
                        GroupsLanes.progress("keep", 0),
                        endsInMinute("67% of minimum · removable ", before, Duration.ofDays(10)))
                .body(GroupsLanes.progressDetail("keep", 0), is("slowest: 20d of 30d · 10d to go"))
                .body(GroupsLanes.card("keep", 0) + ".details.summary.text()", is("2 torrents"))
                .body(GroupsLanes.card("keep", 0) + ".details.@open.text()", is(""))
                .body(
                        GroupsLanes.member("keep", 0, 0),
                        contains("Cross.Seed", "tracker-a · Seeding · 4 B", "67% · 20d of 30d · 10d to go"))
                .body(
                        GroupsLanes.member("keep", 0, 1),
                        contains("Cross.Seed.Elsewhere", "tracker-b · Stopped · 4 B", "93% · 13d of 14d · 1d to go"))
                .body(GroupsLanes.card("keep", 0) + ".details.ul.li[1].small[1].@'data-tone'", is("warn"))
                // A group of one has no members to open: its card shows it whole.
                .body(GroupsLanes.card("keep", 1) + ".details.size()", is(0));
    }

    @Test
    void aCardOffersAKeepOverrideWhoseControlsNameTheGroup() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(GroupsLanes.card("keep", 0) + ".form.@id", is("keep-7777"))
                .body(GroupsLanes.card("keep", 0) + ".form.details.summary", is("Keep…"))
                .body(GroupsLanes.card("remove", 0) + ".form.@action", is("/groups/keep"))
                // Every card: none of these is kept by a keep override.
                .body(GroupsLanes.CARDS + ".collect { it.form.size() }", contains(1, 1, 1, 1, 1, 1, 1, 1))
                // The controls name the group by its first member, so they can be told apart.
                .body(
                        "**.find { it.name() == 'form' && it.@id == 'keep-7777' }.'**'.findAll { it.@'aria-label' != '' }"
                                + ".collect { it.@'aria-label'.toString() }",
                        contains("Note for Cross.Seed", "Until for Cross.Seed", "Set a keep override for Cross.Seed"));
    }

    @Test
    void theLaneOfOneVerdictIsShownAloneAndLinksBackToEveryLane() {
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=remove&q=o")
                .then()
                .statusCode(200)
                .body(GroupsLanes.HEADINGS, is("2 Remove"))
                .body(GroupsLanes.names("remove"), contains("Old.Movie", "ubuntu.iso"))
                .body("html.body.main.form.a.find { it.text() == 'Every verdict' }.@href", is("/groups?q=o"));
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body("html.body.main.form.a.findAll { it.text() == 'Every verdict' }.size()", is(0));
    }

    @Test
    void anEmptyLaneSaysSo() {
        Authenticated.asUser()
                .when()
                .get("/groups?q=Movie")
                .then()
                .statusCode(200)
                .body(GroupsLanes.HEADINGS, contains("2 Keep", "0 Left alone", "1 Remove"))
                .body(GroupsLanes.lane("left-alone") + ".article.size()", is(0))
                .body(GroupsLanes.lane("left-alone") + ".p[1].text()", is("No group of this verdict matches the filter."));
        FakeTransmissionResource.fake()
                .torrents("["
                        + torrent(
                                "6666",
                                "ubuntu.iso",
                                6,
                                1,
                                DAY,
                                false,
                                "udp://open.example:1337/announce",
                                "ubuntu.iso:true")
                        + "]");
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(GroupsLanes.HEADINGS, contains("0 Keep", "0 Left alone", "1 Remove"))
                .body(GroupsLanes.lane("keep") + ".p[1].text()", is("No group has this verdict."));
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
    void pageMayBreakANameOnlyAfterADotOrADash() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(GroupsLanes.card("remove", 1) + ".h3.wbr.size()", is(1))
                .body(GroupsLanes.card("keep", 0) + ".h3.wbr.size()", is(1))
                .body(GroupsLanes.card("keep", 0) + ".details.ul.li[1].span.wbr.size()", is(2));
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
                    .body(GroupsLanes.names("left-alone"), contains("Two.Hosts", "No.Trackers"))
                    .body(
                            GroupsLanes.summary("left-alone", 0),
                            is("Unmanaged (elsewhere.example, tracker-a.example) · Seeding · 0 of 1 files linked · 4 B"))
                    .body(
                            GroupsLanes.summary("left-alone", 1),
                            is("Unmanaged (no trackers) · Seeding · 0 of 1 files linked · 4 B"))
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
                .body(GroupsLanes.names("left-alone"), is("Cross.Seed"))
                .body(GroupsLanes.reason("left-alone", 0), is("Unmanaged"))
                .body(
                        GroupsLanes.hint("left-alone", 0),
                        is("a member is on a tracker not in the config: add it under trackers to manage it"))
                // On the card itself, without opening the members.
                .body(GroupsLanes.causes("left-alone", 0), is("causes \"Unmanaged\" Cross.Seed.Elsewhere"))
                .body(
                        GroupsLanes.member("left-alone", 0, 0),
                        contains("Cross.Seed", "tracker-a · Seeding · 4 B", "Met ✓ · 40d of 30d · met 10d ago"))
                .body(
                        GroupsLanes.member("left-alone", 0, 1),
                        contains(
                                "Cross.Seed.Elsewhere",
                                "causes \"Unmanaged\"",
                                "Unmanaged (elsewhere.example) · Seeding · 4 B",
                                "— · 40d · no minimum"))
                .body(
                        GroupsLanes.card("left-alone", 0) + ".details.ul.li.collect { it.@'data-cause'.text() }",
                        contains("", "true"));
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
                .body("html.body.main.'**'.findAll { it.@id == 'lanes' }.size()", is(0))
                .body("html.body.main.form.size()", is(0));
    }

    @Test
    void theNavigationLeadsToTheGroupsView() {
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .body("html.body.aside.nav.ul.li.a.find { it.@href == '/groups' }.text()", is("Groups"));
    }

    @Test
    void theNavigationMarksTheViewThatIsShown() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .body("html.body.aside.nav.ul.li.a.findAll { it.@'aria-current' == 'page' }.@href", is("/groups"))
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

    /**
     * A text that ends in the same moment as the page writes it: to the minute, in the application's time zone,
     * {@code remaining} after the moment the application decided.
     */
    private static Matcher<String> endsInMinute(String start, Instant before, Duration remaining) {
        DateTimeFormatter format =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(FakeTransmissionResource.TIME_ZONE);
        return new TypeSafeMatcher<>() {
            @Override
            protected boolean matchesSafely(String text) {
                return text.equals(start + format.format(before.plus(remaining)))
                        || text.equals(start + format.format(Instant.now().plus(remaining)));
            }

            @Override
            public void describeTo(Description description) {
                description.appendText(
                        "'" + start + "' and the minute " + format.format(before.plus(remaining)) + " or the next");
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
