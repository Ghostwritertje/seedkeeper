package io.github.ghostwritertje.seedkeeper.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.run.Deletion.Deleted;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What is deleted when torrents have left, and the space that frees, on a real filesystem (spec §4). */
class DeletionTest {

    /** Every file made here is this text: 9 bytes. */
    private static final String CONTENT = "nine byte";

    @TempDir
    Path root;

    private Path downloads;
    private Path incomplete;
    private Path library;
    private Optional<Path> incompleteDir = Optional.empty();

    @BeforeEach
    void directories() throws IOException {
        downloads = Files.createDirectory(root.resolve("downloads"));
        incomplete = Files.createDirectory(root.resolve("incomplete"));
        library = Files.createDirectory(root.resolve("library"));
    }

    @Test
    void aTorrentThatLeftLosesItsFilesAndFreesTheirSize() throws IOException {
        Path movie = file(downloads.resolve("Movie/movie.mkv"));
        Path sample = file(downloads.resolve("Movie/sample.mkv"));
        Path other = file(downloads.resolve("Other/movie.mkv"));

        Deleted deleted = delete(List.of(torrent("a", downloads, "Movie/movie.mkv", "Movie/sample.mkv")),
                List.of(torrent("b", downloads, "Other/movie.mkv")));

        assertGone(movie, sample);
        assertEquals(List.of(movie, sample), deleted.files());
        assertEquals(18, deleted.spaceFreed());
        assertTrue(Files.exists(other), "the file of a remaining torrent was deleted");
    }

    @Test
    void aSymlinkedCrossSeedGroupLeavesNothingAndFreesTheDataOnce() throws IOException {
        Path original = file(downloads.resolve("Original/movie.mkv"));
        Path link = symlink(downloads.resolve("cross-seed/Original/movie.mkv"), original);

        Deleted deleted = delete(List.of(torrent("a", downloads, "Original/movie.mkv"),
                torrent("b", downloads.resolve("cross-seed"), "Original/movie.mkv")), List.of());

        assertGone(original, link);
        assertEquals(9, deleted.spaceFreed());
    }

    @Test
    void aSymlinkedMemberThatLeavesAloneLosesOnlyItsSymlink() throws IOException {
        Path original = file(downloads.resolve("Original/movie.mkv"));
        Path link = symlink(downloads.resolve("cross-seed/Original/movie.mkv"), original);

        Deleted deleted = delete(List.of(torrent("b", downloads.resolve("cross-seed"), "Original/movie.mkv")),
                List.of(torrent("a", downloads, "Original/movie.mkv")));

        assertGone(link);
        assertTrue(Files.exists(original), "the data of the remaining torrent was deleted");
        assertEquals(0, deleted.spaceFreed());
    }

    @Test
    void theDataOfAnUnregisteredSymlinkedOriginalStaysWhileAnotherMemberUsesIt() throws IOException {
        Path original = file(downloads.resolve("Original/movie.mkv"));
        Path link = symlink(downloads.resolve("cross-seed/Original/movie.mkv"), original);
        Torrent unregistered = torrent("a", downloads, "Original/movie.mkv");
        Torrent crossSeed = torrent("b", downloads.resolve("cross-seed"), "Original/movie.mkv");

        Deleted deleted = delete(List.of(unregistered), List.of(crossSeed));

        assertEquals(List.of(), deleted.files());
        assertEquals(0, deleted.spaceFreed());
        assertTrue(Files.exists(original), "the data the cross-seed's symlink leads to was deleted");
        assertEquals(CONTENT, Files.readString(link));

        // The last member leaves: its symlink goes. The data is no path of it, so it is left behind as an orphan.
        Deleted afterTheLast = delete(List.of(crossSeed), List.of());

        assertGone(link);
        assertEquals(List.of(link), afterTheLast.files());
        assertEquals(0, afterTheLast.spaceFreed());
        assertTrue(Files.exists(original));
    }

    @Test
    void aSymlinkARemainingTorrentHasAtThatExactPathStays() throws IOException {
        Path original = file(downloads.resolve("Original/movie.mkv"));
        Path link = symlink(downloads.resolve("cross-seed/Original/movie.mkv"), original);

        // Two torrents with the same path; the data itself belongs to no torrent any more.
        Deleted deleted = delete(List.of(torrent("b", downloads.resolve("cross-seed"), "Original/movie.mkv")),
                List.of(torrent("c", downloads.resolve("cross-seed"), "Original/movie.mkv")));

        assertEquals(List.of(), deleted.files());
        assertTrue(Files.isSymbolicLink(link), "the symlink of a remaining torrent was deleted");
    }

    @Test
    void aHardlinkedCrossSeedGroupLeavesNothingAndFreesTheDataOnce() throws IOException {
        Path original = file(downloads.resolve("Original/movie.mkv"));
        Path link = hardlink(downloads.resolve("cross-seed/Original/movie.mkv"), original);

        Deleted deleted = delete(List.of(torrent("a", downloads, "Original/movie.mkv"),
                torrent("b", downloads.resolve("cross-seed"), "Original/movie.mkv")), List.of());

        assertGone(original, link);
        assertEquals(List.of(original, link), deleted.files());
        assertEquals(9, deleted.spaceFreed());
    }

    @Test
    void aHardlinkedMemberThatLeavesAloneLosesNothing() throws IOException {
        Path original = file(downloads.resolve("Original/movie.mkv"));
        Path link = hardlink(downloads.resolve("cross-seed/Original/movie.mkv"), original);

        Deleted deleted = delete(List.of(torrent("a", downloads, "Original/movie.mkv")),
                List.of(torrent("b", downloads.resolve("cross-seed"), "Original/movie.mkv")));

        assertEquals(List.of(), deleted.files());
        assertEquals(0, deleted.spaceFreed());
        assertTrue(Files.exists(original) && Files.exists(link));
    }

    @Test
    void aFileWithALibraryLinkLivesOnInTheLibraryAndFreesNothing() throws IOException {
        Path download = file(downloads.resolve("Movie/movie.mkv"));
        Path inLibrary = hardlink(library.resolve("Movie (2020)/movie.mkv"), download);

        Deleted deleted = delete(List.of(torrent("a", downloads, "Movie/movie.mkv")), List.of());

        assertGone(download);
        assertEquals(List.of(download), deleted.files());
        assertEquals(0, deleted.spaceFreed());
        assertEquals(CONTENT, Files.readString(inLibrary));
    }

    @Test
    void aFileThatIsNotWantedStaysAndOneOfARemainingTorrentIsKeptThoughNotWanted() throws IOException {
        Path sample = file(downloads.resolve("Movie/sample.mkv"));
        Path movie = file(downloads.resolve("Movie/movie.mkv"));
        Path shared = file(downloads.resolve("Pack/extras.mkv"));
        Torrent left = new Torrent("a", "a", Status.SEEDING, 1, 0, "", 0, true, downloads.toString(), List.of(),
                List.of(new Torrent.File("Movie/sample.mkv", 9, false), new Torrent.File("Movie/movie.mkv", 9, true),
                        new Torrent.File("Pack/extras.mkv", 9, true)));
        Torrent stays = new Torrent("b", "b", Status.SEEDING, 1, 0, "", 0, true, downloads.toString(), List.of(),
                List.of(new Torrent.File("Pack/extras.mkv", 9, false)));

        Deleted deleted = delete(List.of(left), List.of(stays));

        assertEquals(List.of(movie), deleted.files());
        assertTrue(Files.exists(sample), "a file the torrent that left did not want was deleted");
        assertTrue(Files.exists(shared), "a file a remaining torrent still has, though not wanted, was deleted");
    }

    @Test
    void theRootsAreLookedUpWhenDeletingNotWhenTheDeletionIsMade() throws IOException {
        // The downloads root is a symlink, as a mount point can be, that is not there yet when seedkeeper starts.
        Path real = Files.createDirectory(root.resolve("real-downloads"));
        Path mounted = root.resolve("mounted");
        Deletion deletion = new Deletion(mounted, List.of(library));
        Files.createSymbolicLink(mounted, real);
        Path movie = file(mounted.resolve("Movie/movie.mkv"));

        Deleted deleted = deletion.delete(List.of(torrent("a", mounted, "Movie/movie.mkv")), List.of(),
                Optional.empty());

        assertEquals(List.of(movie), deleted.files());
        assertGone(movie);
    }

    @Test
    void aLibraryRootThatCannotBeFoundRefusesEveryDeletion() throws IOException {
        Path movie = file(downloads.resolve("Movie/movie.mkv"));
        Files.delete(library);

        Deleted deleted = delete(List.of(torrent("a", downloads, "Movie/movie.mkv")), List.of());

        assertTrue(Files.exists(movie), "a file was deleted while a library root could not be found");
        assertEquals(List.of(), deleted.files());
        assertEquals(List.of(movie), deleted.refused());
    }

    @Test
    void anIncompleteDirThatIsTheFilesystemRootIsNoPlaceToDeleteIn() throws IOException {
        incompleteDir = Optional.of(root.getRoot());
        Path elsewhere = Files.createDirectory(root.resolve("elsewhere"));
        Path file = file(elsewhere.resolve("Movie/movie.mkv"));

        Deleted deleted = delete(List.of(torrent("a", elsewhere, "Movie/movie.mkv")), List.of());

        assertTrue(Files.exists(file), "a file outside the downloads root was deleted under an incomplete dir of /");
        assertEquals(List.of(file), deleted.refused());
    }

    @Test
    void directoriesThatBecameEmptyAreDeletedUpToButNotIncludingTheDownloadDir() throws IOException {
        Path tv = downloads.resolve("tv");
        Path episode = file(tv.resolve("Show/Season 1/e01.mkv"));
        Path neighbour = file(tv.resolve("Other.Show/e01.mkv"));

        delete(List.of(torrent("a", tv, "Show/Season 1/e01.mkv")), List.of(torrent("b", tv, "Other.Show/e01.mkv")));

        assertGone(episode, tv.resolve("Show/Season 1"), tv.resolve("Show"));
        assertTrue(Files.exists(neighbour));
    }

    @Test
    void theDownloadDirItselfStaysEvenWhenItBecameEmpty() throws IOException {
        Path tv = downloads.resolve("tv");
        Path episode = file(tv.resolve("Show/e01.mkv"));

        delete(List.of(torrent("a", tv, "Show/e01.mkv")), List.of());

        assertGone(episode, tv.resolve("Show"));
        assertTrue(Files.isDirectory(tv), "the download dir was deleted");
        assertTrue(Files.isDirectory(downloads), "the downloads root was deleted");
    }

    @Test
    void aDirectoryThatStillHoldsSomethingStays() throws IOException {
        Path episode = file(downloads.resolve("Show/Season 1/e01.mkv"));
        Path notes = file(downloads.resolve("Show/notes.txt"));

        delete(List.of(torrent("a", downloads, "Show/Season 1/e01.mkv")), List.of());

        assertGone(episode, downloads.resolve("Show/Season 1"));
        assertTrue(Files.exists(notes));
    }

    @Test
    void partFilesInTheIncompleteDirAreDeletedAndTheIncompleteDirStays() throws IOException {
        incompleteDir = Optional.of(incomplete);
        Path part = file(incomplete.resolve("Movie/movie.mkv.part"));
        Path done = file(downloads.resolve("Movie/sample.mkv"));

        Deleted deleted = delete(List.of(torrent("a", downloads, "Movie/movie.mkv", "Movie/sample.mkv")), List.of());

        assertGone(part, done, incomplete.resolve("Movie"), downloads.resolve("Movie"));
        assertEquals(18, deleted.spaceFreed());
        assertTrue(Files.isDirectory(incomplete), "the incomplete dir was deleted");
    }

    @Test
    void nothingOutsideTheDownloadsRootIsDeleted() throws IOException {
        Path elsewhere = Files.createDirectory(root.resolve("elsewhere"));
        Path file = file(elsewhere.resolve("Movie/movie.mkv"));

        Deleted deleted = delete(List.of(torrent("a", elsewhere, "Movie/movie.mkv")), List.of());

        assertTrue(Files.exists(file), "a file outside the downloads root was deleted");
        assertEquals(List.of(), deleted.files());
        assertEquals(0, deleted.spaceFreed());
        assertEquals(List.of(file), deleted.refused());
    }

    @Test
    void nothingInTheIncompleteDirOfAnotherSessionIsDeleted() throws IOException {
        // The incomplete dir is not enabled, so it is not a place seedkeeper deletes in.
        Path file = file(incomplete.resolve("Movie/movie.mkv"));

        Deleted deleted = delete(List.of(torrent("a", incomplete, "Movie/movie.mkv")), List.of());

        assertTrue(Files.exists(file));
        assertEquals(List.of(file), deleted.refused());
    }

    @Test
    void aFileNameThatClimbsOutOfTheDownloadsRootDeletesNothing() throws IOException {
        Path outside = file(root.resolve("elsewhere/movie.mkv"));

        Deleted deleted = delete(List.of(torrent("a", downloads, "../elsewhere/movie.mkv")), List.of());

        assertTrue(Files.exists(outside), "a file outside the downloads root was deleted");
        assertEquals(List.of(outside), deleted.refused());
    }

    @Test
    void nothingUnderALibraryRootIsDeleted() throws IOException {
        Path inLibrary = file(library.resolve("Movie (2020)/movie.mkv"));

        Deleted deleted = delete(List.of(torrent("a", library, "Movie (2020)/movie.mkv")), List.of());

        assertTrue(Files.exists(inLibrary), "a file under a library root was deleted");
        assertEquals(List.of(inLibrary), deleted.refused());
    }

    @Test
    void nothingUnderALibraryRootInsideTheDownloadsRootIsDeleted() throws IOException {
        library = Files.createDirectory(downloads.resolve("library"));
        Path inLibrary = file(library.resolve("Movie (2020)/movie.mkv"));

        Deleted deleted = delete(List.of(torrent("a", downloads, "library/Movie (2020)/movie.mkv")), List.of());

        assertTrue(Files.exists(inLibrary), "a file under a library root was deleted");
        assertEquals(List.of(inLibrary), deleted.refused());
        assertEquals(List.of(), deleted.files());
    }

    @Test
    void aDirectorySymlinkThatLeadsIntoTheLibraryDeletesNothingThere() throws IOException {
        Path inLibrary = file(library.resolve("Movie (2020)/movie.mkv"));
        Path door = symlink(downloads.resolve("Movie"), library.resolve("Movie (2020)"));

        Deleted deleted = delete(List.of(torrent("a", downloads, "Movie/movie.mkv")), List.of());

        assertTrue(Files.exists(inLibrary), "a file under a library root was deleted through a symlinked directory");
        assertEquals(List.of(), deleted.files());
        assertEquals(List.of(downloads.resolve("Movie/movie.mkv")), deleted.refused());
        assertTrue(Files.isSymbolicLink(door));
    }

    @Test
    void aDirectorySymlinkThatLeadsOutOfTheDownloadsRootDeletesNothingThere() throws IOException {
        Path outside = file(root.resolve("elsewhere/Movie/movie.mkv"));
        symlink(downloads.resolve("Movie"), root.resolve("elsewhere/Movie"));

        Deleted deleted = delete(List.of(torrent("a", downloads, "Movie/movie.mkv")), List.of());

        assertTrue(Files.exists(outside), "a file outside the downloads root was deleted");
        assertEquals(List.of(), deleted.files());
    }

    @Test
    void aSymlinkInTheDownloadsRootIsDeletedButNeverWhatItLeadsTo() throws IOException {
        Path inLibrary = file(library.resolve("Movie (2020)/movie.mkv"));
        Path link = symlink(downloads.resolve("Movie/movie.mkv"), inLibrary);

        Deleted deleted = delete(List.of(torrent("a", downloads, "Movie/movie.mkv")), List.of());

        assertGone(link);
        assertEquals(0, deleted.spaceFreed());
        assertEquals(CONTENT, Files.readString(inLibrary));
    }

    private Deleted delete(List<Torrent> removed, List<Torrent> remaining) {
        return new Deletion(downloads, List.of(library)).delete(removed, remaining, incompleteDir);
    }

    private static Torrent torrent(String hash, Path downloadDir, String... files) {
        return new Torrent(hash, hash, Status.SEEDING, 1, 0, "", 0, true, downloadDir.toString(), List.of(),
                Arrays.stream(files).map(name -> new Torrent.File(name, 9, true)).toList());
    }

    private static void assertGone(Path... paths) {
        for (Path path : paths) {
            assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS), path + " is still there");
        }
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, CONTENT);
    }

    private static Path symlink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        return Files.createSymbolicLink(link, target);
    }

    private static Path hardlink(Path link, Path existing) throws IOException {
        Files.createDirectories(link.getParent());
        return Files.createLink(link, existing);
    }
}
