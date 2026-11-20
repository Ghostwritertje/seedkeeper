package io.github.ghostwritertje.seedkeeper.orphans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.orphans.Orphans.Orphan;
import io.github.ghostwritertje.seedkeeper.orphans.OrphansPage.Group;
import io.github.ghostwritertje.seedkeeper.orphans.OrphansPage.Listing;
import io.github.ghostwritertje.seedkeeper.orphans.OrphansPage.Row;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How the Orphans view groups a list of orphans by the directory they are in, without the app. */
class OrphansListingTest {

    private static final Path ROOT = Path.of("/downloads");
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void orphansAreGroupedByTheirTopLevelDirectoryWithACountAndASizeEach() {
        Listing listing = files(
                orphan("leftover.nfo", 512, false),
                orphan("Show/S01/episode.mkv", 2048, false),
                orphan("Show/episode.nfo", 1024, false),
                orphan("cross-seed/movie.mkv", 1, false));

        assertEquals(
                List.of("/downloads", "/downloads/Show", "/downloads/cross-seed"),
                listing.groups().stream().map(Group::directory).toList());
        assertEquals(
                List.of("1 file, 512 B", "2 files, 3.0 KiB", "1 file, 1 B"),
                listing.groups().stream().map(Group::summary).toList());
        assertEquals(
                List.of("S01/episode.mkv", "episode.nfo"),
                listing.groups().get(1).rows().stream().map(Row::path).toList());
        assertEquals("leftover.nfo", listing.groups().getFirst().rows().getFirst().path());
    }

    @Test
    void theTotalCountsEveryOrphanAndItsSizeLeavesOutTheFilesWithALibraryLink() {
        Listing listing = files(orphan("Show/episode.mkv", 2048, true), orphan("Show/episode.nfo", 1024, false));

        assertEquals("2 files", listing.count());
        assertEquals("1.0 KiB", listing.totalSize());
        assertEquals("2 files, 1.0 KiB", listing.groups().getFirst().summary());
        assertEquals(
                List.of("Yes", ""),
                listing.groups().getFirst().rows().stream()
                        .map(Row::libraryLink)
                        .toList());
    }

    @Test
    void theLibraryLinkColumnShowsOnlyWhenAnOrphanHasOne() {
        assertTrue(files(orphan("a.mkv", 1, true), orphan("b.mkv", 1, false)).libraryLinks());
        assertFalse(files(orphan("a.mkv", 1, false)).libraryLinks());
    }

    @Test
    void aRowHasItsSizeAgeAndTarget() {
        Orphan link = new Orphan(
                ROOT.resolve("cross-seed/movie.mkv"),
                50,
                NOW.minus(Duration.ofDays(3).plusHours(2)),
                false,
                Path.of("../gone/movie.mkv"));

        Listing listing = OrphansPage.listing(ROOT, List.of(link), "dangling symlink", "dangling symlinks", NOW);

        assertEquals(
                new Row("movie.mkv", "../gone/movie.mkv", "50 B", "3d 2h", ""),
                listing.groups().getFirst().rows().getFirst());
        assertEquals("1 dangling symlink, 50 B", listing.groups().getFirst().summary());
        assertEquals("1 dangling symlink", listing.count());
    }

    @Test
    void aFileModifiedInTheFutureHasNoAgeYet() {
        Orphan file = new Orphan(ROOT.resolve("a.mkv"), 1, NOW.plus(Duration.ofHours(1)), false, null);

        Listing listing = OrphansPage.listing(ROOT, List.of(file), "file", "files", NOW);

        assertEquals(new Row("a.mkv", "", "1 B", "0m", ""), listing.groups().getFirst().rows().getFirst());
    }

    @Test
    void noOrphansIsNoGroups() {
        Listing listing = files();

        assertTrue(listing.groups().isEmpty());
        assertEquals("0 files", listing.count());
    }

    private static Listing files(Orphan... orphans) {
        return OrphansPage.listing(ROOT, List.of(orphans), "file", "files", NOW);
    }

    private static Orphan orphan(String path, long size, boolean libraryLink) {
        return new Orphan(ROOT.resolve(path), size, NOW, libraryLink, null);
    }
}
