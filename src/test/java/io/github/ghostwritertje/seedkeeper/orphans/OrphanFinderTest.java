package io.github.ghostwritertje.seedkeeper.orphans;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ghostwritertje.seedkeeper.orphans.Orphans.Orphan;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What is an orphan and what is not, on a real filesystem with real symlinks and hardlinks. */
class OrphanFinderTest {

    @TempDir
    Path root;

    private Path downloads;
    private Path incomplete;

    @BeforeEach
    void directories() throws IOException {
        downloads = Files.createDirectory(root.resolve("downloads"));
        incomplete = Files.createDirectory(root.resolve("incomplete"));
    }

    @Test
    void aFileNoTorrentReferencesIsAnOrphan() throws IOException {
        file(downloads.resolve("Seeding/movie.mkv"));
        Path leftover = file(downloads.resolve("Removed/movie.mkv"));

        Orphans orphans = find(torrent("a", wanted("Seeding/movie.mkv")));

        assertEquals(List.of(leftover), paths(orphans.files()));
        assertEquals(List.of(), orphans.danglingSymlinks());
    }

    @Test
    void aDirectoryThatCannotBeReadIsSkippedAndCountedAndTheRestIsStillFound() throws IOException {
        Path leftover = file(downloads.resolve("Removed/movie.mkv"));
        Path locked = Files.createDirectory(downloads.resolve("locked"));
        file(locked.resolve("movie.mkv"));
        Files.setPosixFilePermissions(locked, java.util.Set.of());
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(locked), "running as root reads anything");

            Orphans orphans = find();

            assertEquals(List.of(leftover), paths(orphans.files()));
            assertEquals(List.of(locked), orphans.unreadable());
        } finally {
            Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void anOrphanHasItsSizeAndWhenItWasLastModified() throws IOException {
        Path leftover = Files.writeString(downloads.resolve("leftover.mkv"), "seven b");
        Instant modified = Instant.parse("2026-03-01T10:00:00Z");
        Files.setLastModifiedTime(leftover, FileTime.from(modified));

        assertEquals(List.of(new Orphan(leftover, 7, modified, false)), find().files());
    }

    @Test
    void aDanglingSymlinkNoTorrentReferencesIsAnOrphanOfItsOwnKind() throws IOException {
        Path dangling = symlink(downloads.resolve("cross-seed/movie.mkv"), downloads.resolve("gone/movie.mkv"));

        Orphans orphans = find();

        assertEquals(List.of(dangling), paths(orphans.danglingSymlinks()));
        assertEquals(List.of(), orphans.files());
    }

    @Test
    void aDanglingSymlinkATorrentReferencesIsNotAnOrphan() throws IOException {
        symlink(downloads.resolve("cross-seed/movie.mkv"), downloads.resolve("gone/movie.mkv"));

        Orphans orphans = find(torrent("a", wanted("cross-seed/movie.mkv")));

        assertEquals(List.of(), orphans.danglingSymlinks());
    }

    @Test
    void aFileReferencedAsAPartFileIsNotAnOrphan() throws IOException {
        file(downloads.resolve("movie.mkv.part"));

        assertEquals(List.of(), find(torrent("a", wanted("movie.mkv"))).files());
    }

    @Test
    void aFileReferencedInTheIncompleteDirIsNotAnOrphan() throws IOException {
        Path inside = Files.createDirectory(downloads.resolve("incomplete"));
        Path whole = file(inside.resolve("movie.mkv"));
        Path part = file(inside.resolve("other.mkv.part"));
        List<Torrent> torrents = List.of(torrent("a", wanted("movie.mkv")), torrent("b", wanted("other.mkv")));

        assertEquals(List.of(), OrphanFinder.find(downloads, List.of(), torrents, Optional.of(inside)).files());
        assertEquals(List.of(whole, part),
                paths(OrphanFinder.find(downloads, List.of(), torrents, Optional.empty()).files()));
    }

    @Test
    void aFileOutsideTheDownloadsRootIsNeverListed() throws IOException {
        file(incomplete.resolve("leftover.mkv"));
        file(root.resolve("elsewhere/leftover.mkv"));

        Orphans orphans = OrphanFinder.find(downloads, List.of(), List.of(), Optional.of(incomplete));

        assertEquals(List.of(), orphans.files());
    }

    @Test
    void theFileATorrentsSymlinkLeadsToIsNotAnOrphan() throws IOException {
        Path original = file(downloads.resolve("original/movie.mkv"));
        symlink(downloads.resolve("cross-seed/movie.mkv"), original);

        Orphans orphans = find(torrent("b", wanted("cross-seed/movie.mkv")));

        assertEquals(List.of(), orphans.files());
        assertEquals(List.of(), orphans.danglingSymlinks());
    }

    @Test
    void aHardlinkOfAFileATorrentReferencesIsNotAnOrphan() throws IOException {
        Path original = file(downloads.resolve("original/movie.mkv"));
        Files.createLink(downloads.resolve("copy.mkv"), original);

        assertEquals(List.of(), find(torrent("a", wanted("original/movie.mkv"))).files());
    }

    @Test
    void aFileThatIsNotWantedIsStillReferencedByItsTorrent() throws IOException {
        file(downloads.resolve("Show/episode.mkv"));
        file(downloads.resolve("Show/sample.mkv.part"));

        Orphans orphans = find(torrent("a", wanted("Show/episode.mkv"), unwanted("Show/sample.mkv")));

        assertEquals(List.of(), orphans.files());
    }

    @Test
    void aSymlinkThatLeadsSomewhereIsNotListed() throws IOException {
        Path leftover = file(downloads.resolve("leftover.mkv"));
        symlink(downloads.resolve("link-to-file.mkv"), leftover);
        symlink(downloads.resolve("link-to-dir"), incomplete);
        file(incomplete.resolve("not-under-the-root.mkv"));

        Orphans orphans = find();

        assertEquals(List.of(leftover), paths(orphans.files()));
        assertEquals(List.of(), orphans.danglingSymlinks());
    }

    @Test
    void orphansAreSortedByPathAndTheTotalIsTheSizeOfTheFiles() throws IOException {
        Path b = Files.writeString(Files.createDirectory(downloads.resolve("b")).resolve("movie.mkv"), "12345");
        Path a = Files.writeString(Files.createDirectory(downloads.resolve("a")).resolve("movie.mkv"), "123");
        symlink(downloads.resolve("z.mkv"), downloads.resolve("gone-z.mkv"));
        symlink(downloads.resolve("c.mkv"), downloads.resolve("gone-c.mkv"));

        Orphans orphans = find();

        assertEquals(List.of(a, b), paths(orphans.files()));
        assertEquals(List.of(downloads.resolve("c.mkv"), downloads.resolve("z.mkv")),
                paths(orphans.danglingSymlinks()));
        assertEquals(8, orphans.totalSize());
    }

    @Test
    void aFileUnderALibraryRootInsideTheDownloadsRootIsNotAnOrphan() throws IOException {
        Path library = Files.createDirectory(downloads.resolve("library"));
        file(library.resolve("tv/Show.S01E01.mkv"));
        symlink(library.resolve("tv/gone.mkv"), downloads.resolve("gone.mkv"));
        Path leftover = file(downloads.resolve("leftover.mkv"));

        Orphans orphans = OrphanFinder.find(downloads, List.of(library), List.of(), Optional.empty());

        assertEquals(List.of(leftover), paths(orphans.files()));
        assertEquals(List.of(), orphans.danglingSymlinks());
        assertEquals(4, orphans.totalSize());
    }

    @Test
    void anOrphanFileWithALibraryLinkIsListedAsLibraryLinkedAndLeftOutOfTheTotal() throws IOException {
        Path library = Files.createDirectory(root.resolve("library"));
        Path linked = file(downloads.resolve("Show.S01E01.mkv"));
        Files.createDirectories(library.resolve("tv"));
        Files.createLink(library.resolve("tv/Show.S01E01.mkv"), linked);
        Path leftover = Files.writeString(downloads.resolve("leftover.mkv"), "seven b");

        Orphans orphans = OrphanFinder.find(downloads, List.of(library), List.of(), Optional.empty());

        assertEquals(List.of(linked, leftover), paths(orphans.files()));
        assertEquals(List.of(true, false), orphans.files().stream().map(Orphan::libraryLink).toList());
        assertEquals(7, orphans.totalSize());
    }

    @Test
    void aLibraryRootThatIsMissingGivesTheReason() {
        Path missing = root.resolve("library-not-mounted");

        OrphansException failure = org.junit.jupiter.api.Assertions.assertThrows(OrphansException.class,
                () -> OrphanFinder.find(downloads, List.of(missing), List.of(), Optional.empty()));

        assertEquals("Library root " + missing + " can't be read (NoSuchFileException)", failure.getMessage());
    }

    @Test
    void aDownloadsRootThatIsMissingGivesTheReason() {
        Path missing = root.resolve("not-mounted");

        OrphansException failure = org.junit.jupiter.api.Assertions.assertThrows(OrphansException.class,
                () -> OrphanFinder.find(missing, List.of(), List.of(), Optional.empty()));

        assertEquals("Downloads root " + missing + " can't be read (NoSuchFileException)", failure.getMessage());
    }

    private Orphans find(Torrent... torrents) {
        return OrphanFinder.find(downloads, List.of(), Arrays.asList(torrents), Optional.empty());
    }

    private static List<Path> paths(List<Orphan> orphans) {
        return orphans.stream().map(Orphan::path).toList();
    }

    private Torrent torrent(String hash, Torrent.File... files) {
        return new Torrent(hash, "Torrent " + hash, Status.SEEDING, 1.0, 0, "", 0, true, downloads.toString(),
                List.of("https://tracker-a.example/announce"), List.of(files));
    }

    private static Torrent.File wanted(String name) {
        return new Torrent.File(name, 4, true);
    }

    private static Torrent.File unwanted(String name) {
        return new Torrent.File(name, 4, false);
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "data");
    }

    private static Path symlink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        return Files.createSymbolicLink(link, target);
    }
}
