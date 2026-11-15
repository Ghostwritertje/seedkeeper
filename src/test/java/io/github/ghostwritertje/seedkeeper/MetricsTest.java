package io.github.ghostwritertje.seedkeeper;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmissionResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The metrics on {@code /q/metrics} (spec §10), read without a login the way Prometheus does, around a run started
 * with Run now. The application is shared with other tests, so counters are compared before and after.
 */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class MetricsTest {

    private static final long DAY = 86_400;
    private static final String RUNS = "seedkeeper_runs_total";
    private static final String LAST_SUCCESSFUL_RUN = "seedkeeper_last_successful_run_timestamp_seconds";
    private static final String REMOVED_GROUPS = "seedkeeper_removed_groups_total";
    private static final String SPACE_FREED = "seedkeeper_space_freed_bytes_total";
    private static final String GROUPS = "seedkeeper_groups";

    /** A due group of two cross-seeded torrents (one file of 4 bytes) and a group kept by a library link. */
    @BeforeEach
    void aDueGroupAndAGroupWithALibraryLink() throws IOException {
        Path downloads = FakeTransmissionResource.downloads();
        Path due = file(downloads.resolve("Metrics.Due/movie.mkv"));
        Path dueLink = downloads.resolve("Metrics.cross-seed/Metrics.Due/movie.mkv");
        Files.createDirectories(dueLink.getParent());
        if (!Files.isSymbolicLink(dueLink)) {
            Files.createSymbolicLink(dueLink, due);
        }
        Path linked = file(downloads.resolve("Metrics.Linked/movie.mkv"));
        Path inLibrary = FakeTransmissionResource.library().resolve("Metrics Linked.mkv");
        if (Files.notExists(inLibrary)) {
            Files.createLink(inLibrary, linked);
        }

        FakeTransmissionResource.fake().reset();
        FakeTransmissionResource.fake().torrents("[" + String.join(",",
                torrent("due1", "Metrics.Due", 31 * DAY, downloads, "Metrics.Due/movie.mkv"),
                torrent("linked", "Metrics.Linked", 400 * DAY, downloads, "Metrics.Linked/movie.mkv"),
                torrent("due2", "Metrics.Due.cross-seed", 30 * DAY, downloads.resolve("Metrics.cross-seed"),
                        "Metrics.Due/movie.mkv")) + "]");
    }

    @Test
    void aRunThatSucceedsUpdatesEveryMetric() {
        String before = metrics();
        long justBefore = System.currentTimeMillis() / 1000;

        runNow();

        String after = metrics();
        assertEquals(1, value(after, RUNS, "status=\"succeeded\"") - value(before, RUNS, "status=\"succeeded\""));
        assertEquals(0, value(after, RUNS, "status=\"failed\"") - value(before, RUNS, "status=\"failed\""));
        assertEquals(1, value(after, REMOVED_GROUPS) - value(before, REMOVED_GROUPS));
        assertEquals(4, value(after, SPACE_FREED) - value(before, SPACE_FREED));
        double lastSuccessfulRun = value(after, LAST_SUCCESSFUL_RUN);
        assertTrue(lastSuccessfulRun >= justBefore && lastSuccessfulRun <= System.currentTimeMillis() / 1000 + 1,
                "the last successful run is at " + lastSuccessfulRun + ", not now");
        assertEquals(1, value(after, GROUPS, "verdict=\"remove\"", "reason=\"all-met\""));
        assertEquals(1, value(after, GROUPS, "verdict=\"keep\"", "reason=\"library-link\""));
        assertEquals(0, value(after, GROUPS, "verdict=\"keep\"", "reason=\"minimum-not-reached\""));
        assertEquals(0, value(after, GROUPS, "verdict=\"left-alone\"", "reason=\"unmanaged\""));
    }

    @Test
    void aRunThatFailsIsCountedAsFailedAndLeavesTheRestAlone() {
        runNow();
        String before = metrics();
        FakeTransmissionResource.fake().version("4.0.6 (38c164933e)");

        runNow();

        String after = metrics();
        assertEquals(1, value(after, RUNS, "status=\"failed\"") - value(before, RUNS, "status=\"failed\""));
        assertEquals(0, value(after, RUNS, "status=\"succeeded\"") - value(before, RUNS, "status=\"succeeded\""));
        assertEquals(value(before, LAST_SUCCESSFUL_RUN), value(after, LAST_SUCCESSFUL_RUN));
        assertEquals(0, value(after, REMOVED_GROUPS) - value(before, REMOVED_GROUPS));
        // A failed run has no verdicts: the groups stay as the last run that had them left them.
        assertEquals(1, value(after, GROUPS, "verdict=\"keep\"", "reason=\"library-link\""));
    }

    /** The Prometheus text of {@code /q/metrics}, asked without a login or an API key. */
    private static String metrics() {
        return given().when().get("/q/metrics").then().statusCode(200).extract().asString();
    }

    /** The value of the one sample with this name and all of these labels. */
    private static double value(String metrics, String name, String... labels) {
        List<String> samples = metrics.lines()
                .filter(line -> line.startsWith(name + " ") || line.startsWith(name + "{"))
                .filter(line -> Arrays.stream(labels).allMatch(line::contains))
                .toList();
        assertEquals(1, samples.size(), "samples of " + name + " " + List.of(labels) + ": " + samples);
        String sample = samples.getFirst();
        return Double.parseDouble(sample.substring(sample.lastIndexOf(' ') + 1));
    }

    /** Presses Run now as a logged-in user, with the CSRF token of the form. */
    private static void runNow() {
        Response page = Authenticated.asUser().when().get("/system");
        String token = page.htmlPath().getString("**.find { it.@name == 'csrf-token' }.@value");
        given().redirects().follow(false)
                .cookie(Authenticated.login(TestSecrets.USERNAME, TestSecrets.PASSWORD))
                .cookie("csrf-token", page.cookie("csrf-token"))
                .formParam("csrf-token", token)
                .when().post("/system/run")
                .then().statusCode(303);
    }

    /** A finished, seeding, private torrent of tracker-a with one wanted file. */
    private static String torrent(String hash, String name, long secondsSeeding, Path downloadDir, String file) {
        return """
                {"hash_string": "%s", "name": "%s", "status": 6, "percent_done": 1, "error": 0, "error_string": "",
                 "seconds_seeding": %d, "is_private": true, "download_dir": "%s",
                 "trackers": [{"announce": "https://tracker-a.example/key/announce"}],
                 "files": [{"name": "%s", "length": 4}], "file_stats": [{"wanted": true}]}
                """.formatted(hash, name, secondsSeeding, downloadDir, file);
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }
}
