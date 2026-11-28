package io.github.ghostwritertje.seedkeeper.groups;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Searching the Groups view by name, sorting its lanes, and the first 50 groups of each lane, or one lane alone in pages
 * of 50, end to end.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class GroupsBrowseTest {

    private static final long DAY = 86_400;
    private static final String TRACKER_A = "https://tracker-a.example/key/announce";
    private static final String PAGES = "html.body.main.nav.find { it.@'aria-label' == 'Pages' }";
    private static final String SEARCH = "html.body.main.form.'**'.find { it.name() == 'input' && it.@name == 'q' }";
    private static final String SORT = "html.body.main.form.'**'.find { it.name() == 'select' && it.@name == 'sort' }";
    private static final String REVERSE =
            "html.body.main.form.'**'.find { it.name() == 'input' && it.@name == 'dir' }";

    @BeforeEach
    void torrents() throws IOException {
        for (String name : List.of("Alpha.Movie", "beta.show", "Gamma.Movie", "Delta.Unknown")) {
            file(FakeTransmissionResource.downloads().resolve(name + "/movie.mkv"));
        }
        FakeTransmissionResource.fake().reset();
        FakeTransmissionResource.fake()
                .torrents("["
                        + String.join(
                                ",",
                                // Met: removed.
                                torrent("1111", "Alpha.Movie", 400 * DAY, TRACKER_A, 40),
                                // Kept for its minimum: 10 days to go.
                                torrent("2222", "beta.show", 20 * DAY, TRACKER_A, 10),
                                // Kept for its minimum: 25 days to go.
                                torrent("3333", "Gamma.Movie", 5 * DAY, TRACKER_A, 30),
                                // Unmanaged: left alone.
                                torrent("4444", "Delta.Unknown", 50 * DAY, "https://elsewhere.example/announce", 20))
                        + "]");
    }

    @Test
    void byDefaultEachLaneIsInItsOwnOrder() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(GroupsLanes.names("keep"), contains("beta.show", "Gamma.Movie"))
                .body(GroupsLanes.names("left-alone"), is("Delta.Unknown"))
                .body(GroupsLanes.names("remove"), is("Alpha.Movie"))
                // The order of the lanes is chosen, and there is nothing to turn round.
                .body(selectedSort(), is(""))
                .body(REVERSE + ".@disabled", is("disabled"));
    }

    @Test
    void theSortOffersEveryColumnButTheVerdictWhichIsTheLane() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(SORT + ".option.@value", contains("", "name", "seeding", "forecast", "size"))
                .body(
                        SORT + ".option.collect { it.text() }",
                        contains("Lane order", "Name", "Seed time toward the minimum", "Forecast", "Size"));
    }

    @Test
    void theSortChosenAppliesWithinEveryLaneEitherWay() throws IOException {
        file(FakeTransmissionResource.downloads().resolve("Epsilon.Movie/movie.mkv"));
        FakeTransmissionResource.fake()
                .torrents("["
                        + String.join(
                                ",",
                                torrent("1111", "Alpha.Movie", 400 * DAY, TRACKER_A, 40),
                                torrent("2222", "beta.show", 20 * DAY, TRACKER_A, 10),
                                torrent("3333", "Gamma.Movie", 5 * DAY, TRACKER_A, 30),
                                torrent("4444", "Delta.Unknown", 50 * DAY, "https://elsewhere.example/announce", 20),
                                // Met: removed, and smaller than Alpha.Movie.
                                torrent("5555", "Epsilon.Movie", 400 * DAY, TRACKER_A, 5))
                        + "]");

        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                // Remove largest first.
                .body(GroupsLanes.names("remove"), contains("Alpha.Movie", "Epsilon.Movie"));
        Authenticated.asUser()
                .when()
                .get("/groups?sort=name&dir=desc")
                .then()
                .statusCode(200)
                .body(GroupsLanes.names("keep"), contains("Gamma.Movie", "beta.show"))
                .body(GroupsLanes.names("remove"), contains("Epsilon.Movie", "Alpha.Movie"))
                .body(selectedSort(), is("name"))
                .body(REVERSE + ".@checked.text()", is("checked"))
                .body(REVERSE + ".@disabled.text()", is(""));
        Authenticated.asUser()
                .when()
                .get("/groups?sort=size")
                .then()
                .statusCode(200)
                .body(GroupsLanes.names("keep"), contains("beta.show", "Gamma.Movie"))
                .body(GroupsLanes.names("remove"), contains("Epsilon.Movie", "Alpha.Movie"))
                .body(selectedSort(), is("size"))
                .body(REVERSE + ".@checked.text()", is(""))
                // The keep form keeps the order.
                .body(hidden("sort"), is("size"))
                .body(hidden("dir"), is("asc"));
    }

    @Test
    void pageSearchesTheNamesOfTheMembersAndKeepsTheTextInTheUrl() {
        Authenticated.asUser()
                .when()
                .get("/groups?q=MOVIE")
                .then()
                .statusCode(200)
                .body(GroupsLanes.names("keep"), is("Gamma.Movie"))
                .body(GroupsLanes.names("remove"), is("Alpha.Movie"))
                .body(SEARCH + ".@value", is("MOVIE"))
                .body(SEARCH + ".@type", is("search"))
                // The counts are of the groups the search finds.
                .body(GroupsLanes.HEADINGS, contains("1 Keep", "0 Left alone", "1 Remove"))
                .body("html.body.main.form.a.@href", is("/groups"))
                .body("html.body.main.form.a", is("Clear filter"));
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=keep&q=movie")
                .then()
                .statusCode(200)
                .body(GroupsLanes.HEADINGS, is("1 Keep"))
                .body(GroupsLanes.names("keep"), is("Gamma.Movie"));
        Authenticated.asUser()
                .when()
                .get("/groups?q=nothing.like.it")
                .then()
                .statusCode(200)
                .body("html.body.main.'**'.findAll { it.@id == 'lanes' }.size()", is(0))
                .body("html.body.main.p", is("No group matches the filter."));
    }

    @Test
    void aLaneShowsItsFirstFiftyGroupsAndShowsAllOfThemAloneInPages() throws IOException {
        List<String> names = IntStream.rangeClosed(1, 120)
                .mapToObj(i -> "Bulk.%03d".formatted(i))
                .toList();
        for (String name : names) {
            file(FakeTransmissionResource.downloads().resolve(name + "/movie.mkv"));
        }
        FakeTransmissionResource.fake()
                .torrents("["
                        + names.stream()
                                .map(name -> torrent(name.substring(5), name, 400 * DAY, TRACKER_A, 4))
                                .collect(Collectors.joining(","))
                        + "]");

        Authenticated.asUser()
                .when()
                .get("/groups?sort=name&page=2")
                .then()
                .statusCode(200)
                .body(GroupsLanes.HEADINGS, contains("0 Keep", "0 Left alone", "120 Remove"))
                .body(GroupsLanes.lane("remove") + ".article.size()", is(50))
                .body(GroupsLanes.names("remove") + "[0]", is("Bulk.001"))
                .body(GroupsLanes.lane("remove") + ".a.text()", is("Show all 120 in Remove"))
                .body(GroupsLanes.lane("remove") + ".a.@href", is("/groups?verdict=remove&sort=name&dir=asc"))
                // Every lane at once has no pages.
                .body("html.body.main.nav.findAll { it.@'aria-label' == 'Pages' }.size()", is(0))
                // A page asked for with every lane is the first.
                .body(hidden("page"), is(""));
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=remove&sort=name&page=2")
                .then()
                .statusCode(200)
                .body(GroupsLanes.HEADINGS, is("120 Remove"))
                .body(GroupsLanes.lane("remove") + ".article.size()", is(50))
                .body(GroupsLanes.names("remove") + "[0]", is("Bulk.051"))
                .body(GroupsLanes.lane("remove") + ".a.size()", is(0))
                .body(PAGES + ".p", is("Page 2 of 3 · 120 groups"))
                .body(PAGES + ".a.collect { it.text() }", contains("Previous", "Next"))
                // The pages keep the filter and the order.
                .body(
                        PAGES + ".a.@href",
                        contains(
                                "/groups?verdict=remove&sort=name&dir=asc",
                                "/groups?verdict=remove&sort=name&dir=asc&page=3"))
                .body(hidden("page"), is("2"));
        // Beyond the last page is the last page.
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=remove&page=99")
                .then()
                .statusCode(200)
                .body(GroupsLanes.lane("remove") + ".article.size()", is(20))
                .body(PAGES + ".a.size()", is(1))
                .body(PAGES + ".a", is("Previous"));
    }

    @Test
    void aFewGroupsAreOnePageWithNoLinksBetweenPages() {
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=keep")
                .then()
                .statusCode(200)
                .body("html.body.main.nav.findAll { it.@'aria-label' == 'Pages' }.size()", is(0))
                .body(GroupsLanes.lane("keep") + ".a.size()", is(0));
    }

    /** The value of the selected option of the Sort field; empty for the order of the lanes. */
    private static String selectedSort() {
        return SORT + ".option.find { it.@selected == 'selected' }.@value";
    }

    /** The value of a hidden field of the keep form of the first group of the view. */
    private static String hidden(String name) {
        return GroupsLanes.CARDS + "[0].'**'.find { it.name() == 'input' && it.@name == '" + name + "' }.@value";
    }

    /** One private, seeding torrent with one wanted file, {@code <name>/movie.mkv}, in Transmission's field names. */
    private static String torrent(String hash, String name, long secondsSeeding, String announce, long length) {
        return """
                {"hash_string": "%s", "name": "%s", "status": 6, "percent_done": 1, "error": 0, "error_string": "",
                 "seconds_seeding": %d, "is_private": true, "download_dir": "%s",
                 "trackers": [{"announce": "%s"}], "files": [{"name": "%s/movie.mkv", "length": %d}],
                 "file_stats": [{"wanted": true}]}
                """.formatted(hash, name, secondsSeeding, FakeTransmissionResource.downloads(), announce, name, length);
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }
}
