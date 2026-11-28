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

/** Searching the Groups view by name, sorting it by a column, and its pages of 50 groups, end to end. */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class GroupsBrowseTest {

    private static final long DAY = 86_400;
    private static final String TRACKER_A = "https://tracker-a.example/key/announce";
    private static final String TALLY = "html.body.main.nav.find { it.@'aria-label' == 'Groups by verdict' }";
    private static final String PAGES = "html.body.main.nav.find { it.@'aria-label' == 'Pages' }";
    private static final String SEARCH = "html.body.main.form.'**'.find { it.name() == 'input' && it.@name == 'q' }";

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
    void byDefaultRemoveComesFirstThenLeftAloneThenKeepBySoonestForecast() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(GroupsTable.NAMES, contains("Alpha.Movie", "Delta.Unknown", "beta.show", "Gamma.Movie"))
                // The default order is the verdict's: its header says so.
                .body(sortOf("Verdict"), is("ascending"))
                .body(sorted(), is("Verdict"));
    }

    @Test
    void theHeadersOfTheSortableColumnsLinkToTheirOrder() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body(
                        GroupsTable.HEADINGS + ".collect { it.a.@href.toString() }",
                        contains(
                                "/groups?sort=name&dir=asc",
                                "/groups?sort=seeding&dir=asc",
                                // The order shown: a click turns it round.
                                "/groups?sort=verdict&dir=desc",
                                "",
                                "/groups?sort=forecast&dir=asc",
                                "/groups?sort=size&dir=asc",
                                ""));
    }

    @Test
    void pageSortsByTheColumnChosenEitherWay() {
        Authenticated.asUser()
                .when()
                .get("/groups?sort=name")
                .then()
                .statusCode(200)
                .body(GroupsTable.NAMES, contains("Alpha.Movie", "beta.show", "Delta.Unknown", "Gamma.Movie"))
                .body(sortOf("Name"), is("ascending"))
                .body(sorted(), is("Name"));
        Authenticated.asUser()
                .when()
                .get("/groups?sort=size&dir=desc")
                .then()
                .statusCode(200)
                .body(GroupsTable.NAMES, contains("Alpha.Movie", "Gamma.Movie", "Delta.Unknown", "beta.show"))
                .body(sortOf("Size"), is("descending"))
                .body(GroupsTable.HEADINGS + ".find { it.text() == 'Size' }.a.@href", is("/groups?sort=size&dir=asc"))
                // Filtering keeps the order.
                .body(TALLY + ".a[1].@href", is("/groups?verdict=remove&sort=size&dir=desc"))
                .body(hidden("sort"), is("size"))
                .body(hidden("dir"), is("desc"));
        Authenticated.asUser()
                .when()
                .get("/groups?sort=seeding")
                .then()
                .statusCode(200)
                // The unmanaged group has no minimum to go toward: it comes last.
                .body(GroupsTable.NAMES, contains("Gamma.Movie", "beta.show", "Alpha.Movie", "Delta.Unknown"));
    }

    @Test
    void pageSearchesTheNamesOfTheMembersAndKeepsTheTextInTheUrl() {
        Authenticated.asUser()
                .when()
                .get("/groups?q=MOVIE")
                .then()
                .statusCode(200)
                .body(GroupsTable.NAMES, contains("Alpha.Movie", "Gamma.Movie"))
                .body(SEARCH + ".@value", is("MOVIE"))
                .body(SEARCH + ".@type", is("search"))
                // The counts are of the groups the search finds.
                .body(TALLY + ".a.collect { it.text() }", contains("2 All", "1 Remove", "0 Left alone", "1 Keep"))
                .body(TALLY + ".a[3].@href", is("/groups?verdict=keep&q=MOVIE"))
                .body(
                        GroupsTable.HEADINGS + ".find { it.text() == 'Name' }.a.@href",
                        is("/groups?q=MOVIE&sort=name&dir=asc"))
                .body("html.body.main.form.a.@href", is("/groups"))
                .body("html.body.main.form.a", is("Clear filter"));
        Authenticated.asUser()
                .when()
                .get("/groups?verdict=keep&q=movie")
                .then()
                .statusCode(200)
                .body(GroupsTable.NAMES, is("Gamma.Movie"));
        Authenticated.asUser()
                .when()
                .get("/groups?q=nothing.like.it")
                .then()
                .statusCode(200)
                .body("html.body.main.'**'.findAll { it.name() == 'table' }.size()", is(0))
                .body("html.body.main.p", is("No group matches the filter."));
    }

    @Test
    void pageShowsFiftyGroupsAPage() throws IOException {
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
                .get("/groups")
                .then()
                .statusCode(200)
                .body(GroupsTable.GROUPS + ".size()", is(50))
                .body(GroupsTable.NAMES + "[0]", is("Bulk.001"))
                .body(PAGES + ".p", is("Page 1 of 3 · 120 groups"))
                .body(PAGES + ".a.size()", is(1))
                .body(PAGES + ".a", is("Next"))
                .body(PAGES + ".a.@href", is("/groups?page=2"));
        Authenticated.asUser()
                .when()
                .get("/groups?sort=name&page=2")
                .then()
                .statusCode(200)
                .body(GroupsTable.GROUPS + ".size()", is(50))
                .body(GroupsTable.NAMES + "[0]", is("Bulk.051"))
                .body(PAGES + ".p", is("Page 2 of 3 · 120 groups"))
                .body(PAGES + ".a.collect { it.text() }", contains("Previous", "Next"))
                // The pages keep the order; sorting starts again on the first page.
                .body(PAGES + ".a.@href", contains("/groups?sort=name&dir=asc", "/groups?sort=name&dir=asc&page=3"))
                .body(GroupsTable.HEADINGS + ".find { it.text() == 'Name' }.a.@href", is("/groups?sort=name&dir=desc"))
                .body(hidden("page"), is("2"));
        // Beyond the last page is the last page.
        Authenticated.asUser()
                .when()
                .get("/groups?page=99")
                .then()
                .statusCode(200)
                .body(GroupsTable.GROUPS + ".size()", is(20))
                .body(GroupsTable.NAMES + "[0]", is("Bulk.101"))
                .body(PAGES + ".a.size()", is(1))
                .body(PAGES + ".a", is("Previous"));
    }

    @Test
    void aFewGroupsAreOnePageWithNoLinksBetweenPages() {
        Authenticated.asUser()
                .when()
                .get("/groups")
                .then()
                .statusCode(200)
                .body("html.body.main.nav.findAll { it.@'aria-label' == 'Pages' }.size()", is(0));
    }

    /** The {@code aria-sort} of the column header with this text; empty when it has none. */
    private static String sortOf(String header) {
        return GroupsTable.HEADINGS + ".find { it.text() == '" + header + "' }.@'aria-sort'";
    }

    /** The text of every column header that has an {@code aria-sort}, joined: only one ever has. */
    private static String sorted() {
        return GroupsTable.HEADINGS + ".findAll { it.@'aria-sort' != '' }.collect { it.text() }.join(',')";
    }

    /** The value of a hidden field of the keep form of the first group. */
    private static String hidden(String name) {
        return GroupsTable.GROUPS + "[0].'**'.find { it.name() == 'input' && it.@name == '" + name + "' }.@value";
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
