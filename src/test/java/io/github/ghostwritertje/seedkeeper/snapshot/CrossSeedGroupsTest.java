package io.github.ghostwritertje.seedkeeper.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

/** Cross-seed groups (spec §3.3) on a real filesystem, with real hardlinks and symlinks. */
class CrossSeedGroupsTest {

    @TempDir
    Path root;

    private Path downloads;
    private Path library;

    @BeforeEach
    void directories() throws IOException {
        downloads = Files.createDirectory(root.resolve("downloads"));
        library = Files.createDirectory(root.resolve("library"));
    }

    @Test
    void torrentsWithTheirOwnFilesAreEachAGroupInSnapshotOrder() throws IOException {
        file(downloads.resolve("one/movie.mkv"));
        file(downloads.resolve("two/movie.mkv"));

        assertEquals(List.of(List.of("a"), List.of("b")),
                groups(torrent("a", wanted("one/movie.mkv")), torrent("b", wanted("two/movie.mkv"))));
    }

    @Test
    void aSymlinkedCrossSeedIsInTheGroupOfTheTorrentItPointsTo() throws IOException {
        Path original = file(downloads.resolve("original/movie.mkv"));
        file(downloads.resolve("other/movie.mkv"));
        symlink(downloads.resolve("cross-seed/movie.mkv"), original);

        assertEquals(List.of(List.of("a", "c"), List.of("b")),
                groups(torrent("a", wanted("original/movie.mkv")), torrent("b", wanted("other/movie.mkv")),
                        torrent("c", wanted("cross-seed/movie.mkv"))));
    }

    @Test
    void aHardlinkedCrossSeedIsInTheGroupOfTheTorrentItSharesAFileWith() throws IOException {
        Path original = file(downloads.resolve("original/movie.mkv"));
        hardlink(downloads.resolve("cross-seed/movie.mkv"), original);

        assertEquals(List.of(List.of("a", "b")),
                groups(torrent("a", wanted("original/movie.mkv")), torrent("b", wanted("cross-seed/movie.mkv"))));
    }

    @Test
    void oneSharedFileOfManyIsEnough() throws IOException {
        file(downloads.resolve("season/e01.mkv"));
        Path second = file(downloads.resolve("season/e02.mkv"));
        hardlink(downloads.resolve("episode/e02.mkv"), second);
        file(downloads.resolve("episode/e02.nfo"));

        assertEquals(List.of(List.of("a", "b")),
                groups(torrent("a", wanted("season/e01.mkv"), wanted("season/e02.mkv")),
                        torrent("b", wanted("episode/e02.nfo"), wanted("episode/e02.mkv"))));
    }

    @Test
    void aChainOfThreeIsOneGroupThoughTheEndsShareNoFile() throws IOException {
        // a and b share e01 (hardlink), b and c share e02 (symlink); a and c share nothing.
        Path first = file(downloads.resolve("a/e01.mkv"));
        hardlink(downloads.resolve("b/e01.mkv"), first);
        Path second = file(downloads.resolve("b/e02.mkv"));
        symlink(downloads.resolve("c/e02.mkv"), second);
        file(downloads.resolve("d/e03.mkv"));

        assertEquals(List.of(List.of("c", "a", "b"), List.of("d")),
                groups(torrent("c", wanted("c/e02.mkv")), torrent("a", wanted("a/e01.mkv")),
                        torrent("d", wanted("d/e03.mkv")),
                        torrent("b", wanted("b/e01.mkv"), wanted("b/e02.mkv"))));
    }

    @Test
    void aSharedFileThatOneTorrentDoesNotWantJoinsNoGroup() throws IOException {
        Path sample = file(downloads.resolve("original/sample.mkv"));
        file(downloads.resolve("original/movie.mkv"));
        hardlink(downloads.resolve("cross-seed/sample.mkv"), sample);

        assertEquals(List.of(List.of("a"), List.of("b")),
                groups(torrent("a", wanted("original/movie.mkv"), unwanted("original/sample.mkv")),
                        torrent("b", wanted("cross-seed/sample.mkv"))));
    }

    @Test
    void aSharedFileThatNeitherTorrentWantsJoinsNoGroup() throws IOException {
        Path sample = file(downloads.resolve("original/sample.mkv"));
        file(downloads.resolve("original/movie.mkv"));
        hardlink(downloads.resolve("cross-seed/sample.mkv"), sample);
        file(downloads.resolve("cross-seed/movie.mkv"));

        assertEquals(List.of(List.of("a"), List.of("b")),
                groups(torrent("a", wanted("original/movie.mkv"), unwanted("original/sample.mkv")),
                        torrent("b", wanted("cross-seed/movie.mkv"), unwanted("cross-seed/sample.mkv"))));
    }

    @Test
    void torrentsWithNoResolvableFileAreEachAGroupOfTheirOwn() throws IOException {
        symlink(downloads.resolve("dangling/movie.mkv"), downloads.resolve("removed/movie.mkv"));

        assertEquals(List.of(List.of("a"), List.of("b"), List.of("c")),
                groups(torrent("a", wanted("gone/movie.mkv")), torrent("b", wanted("gone/movie.mkv")),
                        torrent("c", wanted("dangling/movie.mkv"))));
    }

    @Test
    void aLibraryLinkAloneJoinsNoGroup() throws IOException {
        Path one = file(downloads.resolve("one/movie.mkv"));
        Path two = file(downloads.resolve("two/movie.mkv"));
        hardlink(library.resolve("one.mkv"), one);
        hardlink(library.resolve("two.mkv"), two);

        assertEquals(List.of(List.of("a"), List.of("b")),
                groups(torrent("a", wanted("one/movie.mkv")), torrent("b", wanted("two/movie.mkv"))));
    }

    /** The hashes of the members of each group. */
    private List<List<String>> groups(Torrent... torrents) {
        Snapshot snapshot = SnapshotBuilder.build(Arrays.asList(torrents), Optional.empty(), List.of(library));
        return snapshot.groups().stream()
                .map(group -> group.stream().map(member -> member.torrent().hash()).toList())
                .toList();
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

    private static void hardlink(Path link, Path existing) throws IOException {
        Files.createDirectories(link.getParent());
        Files.createLink(link, existing);
    }

    private static void symlink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, target);
    }
}
