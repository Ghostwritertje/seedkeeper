package io.github.ghostwritertje.seedkeeper.orphans;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import io.github.ghostwritertje.seedkeeper.Authenticated;
import io.github.ghostwritertje.seedkeeper.ScrollRegions;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmission.Behaviour;
import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Orphans view and {@code GET /api/v1/orphans}, end to end: one torrent in the fake Transmission, and in the
 * downloads root its file, two files no torrent references and a dangling symlink.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class OrphansTest {

    private Path downloads;

    @BeforeEach
    void downloads() throws IOException {
        downloads = FakeTransmissionResource.downloads();
        clear(downloads);
        file(downloads.resolve("Seeding/movie.mkv"), 4, Instant.now());
        file(downloads.resolve("Removed/movie.mkv"), 1536, Instant.parse("2026-03-01T10:00:00Z"));
        file(
                downloads.resolve("leftover.nfo"),
                512,
                Instant.now().minus(Duration.ofDays(3).plusHours(2).plusMinutes(30)));
        Files.createSymbolicLink(
                Files.createDirectory(downloads.resolve("cross-seed")).resolve("movie.mkv"),
                downloads.resolve("gone/movie.mkv"));

        FakeTransmissionResource.fake().reset();
        FakeTransmissionResource.fake().torrents("""
                [{"hash_string": "1111", "name": "Seeding", "status": 6, "percent_done": 1, "error": 0,
                  "error_string": "", "seconds_seeding": 0, "is_private": true, "download_dir": "%s",
                  "trackers": [{"announce": "https://tracker-a.example/announce"}],
                  "files": [{"name": "Seeding/movie.mkv", "length": 4}], "file_stats": [{"wanted": true}]}]
                """.formatted(downloads));
    }

    @Test
    void apiGivesTheFilesAndTheDanglingSymlinksSeparatelyWithATotal() {
        Authenticated.withApiKey()
                .when()
                .get("/api/v1/orphans")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body(
                        "files.path",
                        contains(
                                downloads.resolve("Removed/movie.mkv").toString(),
                                downloads.resolve("leftover.nfo").toString()))
                .body("files.sizeBytes", contains(1536, 512))
                .body("files[0].lastModified", is("2026-03-01T10:00:00Z"))
                .body(
                        "danglingSymlinks.path",
                        contains(downloads.resolve("cross-seed/movie.mkv").toString()))
                .body("totalSizeBytes", is(2048));
    }

    /** The library root is shared with the other test classes: leave it as empty as it was found. */
    @AfterEach
    void emptyTheLibrary() throws IOException {
        clear(FakeTransmissionResource.library());
    }

    @Test
    void apiMarksAnOrphanFileWithALibraryLinkAndLeavesItOutOfTheTotal() throws IOException {
        Path library = FakeTransmissionResource.library();
        Files.createLink(
                Files.createDirectory(library.resolve("movies")).resolve("movie.mkv"),
                downloads.resolve("Removed/movie.mkv"));

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/orphans")
                .then()
                .statusCode(200)
                .body(
                        "files.path",
                        contains(
                                downloads.resolve("Removed/movie.mkv").toString(),
                                downloads.resolve("leftover.nfo").toString()))
                .body("files.libraryLink", contains(true, false))
                .body("danglingSymlinks.libraryLink", contains(false))
                .body("totalSizeBytes", is(512));
    }

    @Test
    void pageMarksAnOrphanFileWithALibraryLinkAndLeavesItOutOfTheTotal() throws IOException {
        Path library = FakeTransmissionResource.library();
        Files.createLink(
                Files.createDirectory(library.resolve("movies")).resolve("movie.mkv"),
                downloads.resolve("Removed/movie.mkv"));
        String files = "html.body.main.section.find { it.@id == 'files' }";

        Authenticated.asUser()
                .when()
                .get("/orphans")
                .then()
                .statusCode(200)
                .body(files + ".div.table.thead.tr.th", contains("Path", "Size", "Age", "Library link"))
                .body(files + ".div.table.tbody.tr[0].td[3]", is("Yes"))
                .body(files + ".div.table.tbody.tr[1].td[3]", is(""))
                .body(files + ".div.table.tfoot.tr.td[0]", is("512 B"));
    }

    @Test
    void apiGivesEmptyListsWhenThereAreNoOrphans() throws IOException {
        clear(downloads);

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/orphans")
                .then()
                .statusCode(200)
                .body("files", empty())
                .body("danglingSymlinks", empty())
                .body("totalSizeBytes", is(0));
    }

    @Test
    void apiGivesTheReasonWhenTransmissionCannotBeUsed() {
        FakeTransmissionResource.fake().version("4.0.6 (38c164933e)");

        Authenticated.withApiKey()
                .when()
                .get("/api/v1/orphans")
                .then()
                .statusCode(503)
                .contentType("application/json")
                .body(
                        "reason",
                        is("Transmission 4.0.6 (38c164933e) is older than 4.1,"
                                + " the oldest version seedkeeper works with"));
    }

    @Test
    void pageListsTheFilesAndTheDanglingSymlinksSeparatelyWithATotal() {
        String files = "html.body.main.section.find { it.@id == 'files' }";
        String symlinks = "html.body.main.section.find { it.@id == 'dangling-symlinks' }";
        Authenticated.asUser()
                .when()
                .get("/orphans")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body("html.head.title", is("Orphans · seedkeeper"))
                .body(ScrollRegions.TABLE_PARENTS, ScrollRegions.regions("Orphan files", "Dangling symlinks"))
                .body(files + ".h2", is("Files"))
                .body(files + ".div.table.thead.tr.th", contains("Path", "Size", "Age", "Library link"))
                .body(files + ".div.table.tbody.tr.size()", is(2))
                .body(
                        files + ".div.table.tbody.tr[0].td[0]",
                        is(downloads.resolve("Removed/movie.mkv").toString()))
                .body(files + ".div.table.tbody.tr[0].td[1]", is("1.5 KiB"))
                .body(
                        files + ".div.table.tbody.tr[1].td[0]",
                        is(downloads.resolve("leftover.nfo").toString()))
                .body(files + ".div.table.tbody.tr[1].td[1]", is("512 B"))
                .body(files + ".div.table.tbody.tr[1].td[2]", is("3d 2h"))
                .body(files + ".div.table.tbody.tr[1].td[3]", is(""))
                .body(files + ".div.table.tfoot.tr.th", is("Total of 2 files"))
                .body(files + ".div.table.tfoot.tr.td[0]", is("2.0 KiB"))
                .body(symlinks + ".h2", is("Dangling symlinks"))
                .body(symlinks + ".div.table.tbody.tr.size()", is(1))
                .body(
                        symlinks + ".div.table.tbody.tr[0].td[0]",
                        is(downloads.resolve("cross-seed/movie.mkv").toString()))
                .body(symlinks + ".div.table.tfoot.tr.th", is("Total of 1 dangling symlink"));
    }

    @Test
    void pageSaysSoWhenThereAreNoOrphans() throws IOException {
        clear(downloads);

        Authenticated.asUser()
                .when()
                .get("/orphans")
                .then()
                .statusCode(200)
                .body("html.body.main.section.find { it.@id == 'files' }.p", is("No file is an orphan."))
                .body(
                        "html.body.main.section.find { it.@id == 'dangling-symlinks' }.p",
                        is("No dangling symlink is an orphan."))
                .body("html.body.main.'**'.findAll { it.name() == 'table' }.size()", is(0));
    }

    @Test
    void pageGivesTheReasonWhenTransmissionCannotBeUsed() {
        FakeTransmissionResource.fake().behaviour(Behaviour.RPC_ERROR);

        Authenticated.asUser()
                .when()
                .get("/orphans")
                .then()
                .statusCode(200)
                .body(
                        "html.body.main.p.find { it.@role == 'alert' }",
                        containsString("refused session_get: Method not found"))
                .body("html.body.main.section.size()", is(0));
    }

    @Test
    void theNavigationLeadsToTheOrphansView() {
        Authenticated.asUser()
                .when()
                .get("/system")
                .then()
                .body("html.body.header.nav.a.find { it.@href == '/orphans' }", is("Orphans"));
    }

    private static void file(Path path, int size, Instant modified) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[size]);
        Files.setLastModifiedTime(path, FileTime.from(modified));
    }

    /** Removes everything under the directory, also what another test class left there. */
    private static void clear(Path directory) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(directory)) {
            paths = walk.filter(path -> !path.equals(directory))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }
}
