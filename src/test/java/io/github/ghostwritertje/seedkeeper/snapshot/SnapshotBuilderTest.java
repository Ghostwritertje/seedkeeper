package io.github.ghostwritertje.seedkeeper.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.SnapshotTorrent;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.WantedFile;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Path resolution and the library index (spec §3.1) on a real filesystem, with real hardlinks and symlinks. */
class SnapshotBuilderTest {

    @TempDir
    Path root;

    private Path downloads;
    private Path incomplete;
    private Path library;

    @BeforeEach
    void directories() throws IOException {
        downloads = Files.createDirectory(root.resolve("downloads"));
        incomplete = Files.createDirectory(root.resolve("incomplete"));
        library = Files.createDirectory(root.resolve("library"));
    }

    @Test
    void aFileWithAHardlinkInTheLibraryHasALibraryLink() throws IOException {
        Path download = file(downloads.resolve("Show/episode.mkv"));
        hardlink(library.resolve("tv/Show/Season 01/episode.mkv"), download);

        Snapshot snapshot = build(torrent("a", wanted("Show/episode.mkv")));

        assertTrue(snapshot.hasLibraryLink(onlyFile(snapshot)));
    }

    @Test
    void aFileWithoutALinkInTheLibraryHasNoLibraryLink() throws IOException {
        file(downloads.resolve("Show/episode.mkv"));
        file(library.resolve("tv/Show/another-episode.mkv"));

        Snapshot snapshot = build(torrent("a", wanted("Show/episode.mkv")));

        assertTrue(onlyFile(snapshot).id().isPresent());
        assertFalse(snapshot.hasLibraryLink(onlyFile(snapshot)));
    }

    @Test
    void aHardlinkBetweenTwoDownloadsIsNotALibraryLink() throws IOException {
        Path original = file(downloads.resolve("original/movie.mkv"));
        hardlink(downloads.resolve("cross-seed/movie.mkv"), original);

        Snapshot snapshot =
                build(torrent("a", wanted("original/movie.mkv")), torrent("b", wanted("cross-seed/movie.mkv")));

        WantedFile first = snapshot.torrents().get(0).wantedFiles().get(0);
        WantedFile second = snapshot.torrents().get(1).wantedFiles().get(0);
        assertTrue(first.id().isPresent());
        assertEquals(first.id(), second.id());
        assertFalse(snapshot.hasLibraryLink(first));
        assertFalse(snapshot.hasLibraryLink(second));
    }

    @Test
    void aSymlinkBetweenTwoDownloadsResolvesToTheSameFileAndIsNotALibraryLink() throws IOException {
        Path original = file(downloads.resolve("original/movie.mkv"));
        symlink(downloads.resolve("cross-seed/movie.mkv"), original);

        Snapshot snapshot =
                build(torrent("a", wanted("original/movie.mkv")), torrent("b", wanted("cross-seed/movie.mkv")));

        WantedFile first = snapshot.torrents().get(0).wantedFiles().get(0);
        WantedFile second = snapshot.torrents().get(1).wantedFiles().get(0);
        assertTrue(first.id().isPresent());
        assertEquals(first.id(), second.id());
        assertFalse(snapshot.hasLibraryLink(second));
    }

    @Test
    void aSymlinkedDownloadHasALibraryLinkWhenTheFileItPointsToHasOne() throws IOException {
        Path original = file(downloads.resolve("original/movie.mkv"));
        symlink(downloads.resolve("cross-seed/movie.mkv"), original);
        hardlink(library.resolve("movies/movie.mkv"), original);

        Snapshot snapshot = build(torrent("b", wanted("cross-seed/movie.mkv")));

        assertTrue(snapshot.hasLibraryLink(onlyFile(snapshot)));
    }

    @Test
    void aSymlinkInTheLibraryIsNotInTheLibraryIndex() throws IOException {
        Path download = file(downloads.resolve("movie.mkv"));
        symlink(library.resolve("movies/movie.mkv"), download);
        Files.createSymbolicLink(library.resolve("all-downloads"), downloads);

        Snapshot snapshot = build(torrent("a", wanted("movie.mkv")));

        assertFalse(snapshot.hasLibraryLink(onlyFile(snapshot)));
        assertEquals(0, snapshot.library().size());
    }

    @Test
    void everyLibraryRootIsIndexed() throws IOException {
        Path secondLibrary = Files.createDirectory(root.resolve("library-2"));
        Path download = file(downloads.resolve("movie.mkv"));
        hardlink(secondLibrary.resolve("deep/down/movie.mkv"), download);

        Snapshot snapshot = SnapshotBuilder.build(
                List.of(torrent("a", wanted("movie.mkv"))), Optional.empty(), List.of(library, secondLibrary));

        assertTrue(snapshot.hasLibraryLink(onlyFile(snapshot)));
    }

    @Test
    void aFileThatIsNotWantedPlaysNoPart() throws IOException {
        Path sample = file(downloads.resolve("Show/sample.mkv"));
        hardlink(library.resolve("sample.mkv"), sample);
        file(downloads.resolve("Show/episode.mkv"));

        Snapshot snapshot = build(torrent("a", unwanted("Show/sample.mkv"), wanted("Show/episode.mkv")));

        assertEquals(
                List.of("Show/episode.mkv"),
                snapshot.torrents().get(0).wantedFiles().stream()
                        .map(WantedFile::name)
                        .toList());
        assertFalse(snapshot.hasLibraryLink(onlyFile(snapshot)));
    }

    @Test
    void aPartFileInTheDownloadDirIsFound() throws IOException {
        Path part = file(downloads.resolve("movie.mkv.part"));

        Snapshot snapshot = build(torrent("a", wanted("movie.mkv")));

        assertEquals(Optional.of(id(part)), onlyFile(snapshot).id());
    }

    @Test
    void aFileInTheIncompleteDirIsFoundWhenTheIncompleteDirIsEnabled() throws IOException {
        Path inIncomplete = file(incomplete.resolve("movie.mkv"));

        assertEquals(
                Optional.of(id(inIncomplete)),
                onlyFile(buildWithIncompleteDir(torrent("a", wanted("movie.mkv"))))
                        .id());
        assertEquals(
                Optional.empty(),
                onlyFile(build(torrent("a", wanted("movie.mkv")))).id());
    }

    @Test
    void aPartFileInTheIncompleteDirIsFound() throws IOException {
        Path part = file(incomplete.resolve("movie.mkv.part"));

        assertEquals(
                Optional.of(id(part)),
                onlyFile(buildWithIncompleteDir(torrent("a", wanted("movie.mkv"))))
                        .id());
    }

    @Test
    void theFirstCandidateThatExistsWins() throws IOException {
        Path inIncompletePart = file(incomplete.resolve("movie.mkv.part"));
        Torrent torrent = torrent("a", wanted("movie.mkv"));
        assertEquals(
                Optional.of(id(inIncompletePart)),
                onlyFile(buildWithIncompleteDir(torrent)).id());

        Path inIncomplete = file(incomplete.resolve("movie.mkv"));
        assertEquals(
                Optional.of(id(inIncomplete)),
                onlyFile(buildWithIncompleteDir(torrent)).id());

        Path part = file(downloads.resolve("movie.mkv.part"));
        assertEquals(
                Optional.of(id(part)), onlyFile(buildWithIncompleteDir(torrent)).id());

        Path complete = file(downloads.resolve("movie.mkv"));
        assertEquals(
                Optional.of(id(complete)),
                onlyFile(buildWithIncompleteDir(torrent)).id());
        assertNotEquals(id(complete), id(part));
    }

    @Test
    void aFileThatIsNowhereResolvesToNothing() {
        Snapshot snapshot = build(torrent("a", wanted("gone.mkv")));

        assertEquals(Optional.empty(), onlyFile(snapshot).id());
        assertFalse(snapshot.hasLibraryLink(onlyFile(snapshot)));
    }

    @Test
    void aDanglingSymlinkResolvesToNothing() throws IOException {
        symlink(downloads.resolve("movie.mkv"), downloads.resolve("removed-original/movie.mkv"));

        assertEquals(
                Optional.empty(),
                onlyFile(build(torrent("a", wanted("movie.mkv")))).id());
    }

    @Test
    void aLibraryRootThatIsMissingFailsTheSnapshot() {
        Path missing = root.resolve("not-mounted");

        SnapshotException failure = assertThrows(
                SnapshotException.class,
                () -> SnapshotBuilder.build(List.of(), Optional.empty(), List.of(library, missing)));

        assertEquals("Library root " + missing + " can't be read (NoSuchFileException)", failure.getMessage());
    }

    private Snapshot build(Torrent... torrents) {
        return SnapshotBuilder.build(Arrays.asList(torrents), Optional.empty(), List.of(library));
    }

    private Snapshot buildWithIncompleteDir(Torrent... torrents) {
        return SnapshotBuilder.build(Arrays.asList(torrents), Optional.of(incomplete), List.of(library));
    }

    private static WantedFile onlyFile(Snapshot snapshot) {
        assertEquals(1, snapshot.torrents().size());
        SnapshotTorrent torrent = snapshot.torrents().get(0);
        assertEquals(1, torrent.wantedFiles().size());
        return torrent.wantedFiles().get(0);
    }

    private Torrent torrent(String hash, Torrent.File... files) {
        return new Torrent(
                hash,
                "Torrent " + hash,
                Status.SEEDING,
                1.0,
                0,
                "",
                0,
                true,
                downloads.toString(),
                List.of("https://tracker-a.example/announce"),
                List.of(files));
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

    private static void hardlink(Path link, Path existing) throws IOException {
        Files.createDirectories(link.getParent());
        Files.createLink(link, existing);
    }

    private static void symlink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, target);
    }

    /** The file's device and inode, read the way {@code ls -i} would. */
    private static FileId id(Path path) throws IOException {
        return new FileId((Long) Files.getAttribute(path, "unix:dev"), (Long) Files.getAttribute(path, "unix:ino"));
    }
}
