package io.github.ghostwritertje.seedkeeper.snapshot;

import io.github.ghostwritertje.seedkeeper.ModuleApi;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.SnapshotTorrent;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.WantedFile;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reads the filesystem part of a snapshot (spec §3.1): where each wanted file is, and the library index. */
@ModuleApi
public final class SnapshotBuilder {

    private SnapshotBuilder() {}

    /**
     * The snapshot of these torrents: where each wanted file is on disk, and the library index.
     *
     * @param incompleteDir Transmission's incomplete dir; empty when it is not enabled
     * @throws SnapshotException when a library root can't be read: without it every library link would seem gone
     */
    public static Snapshot build(List<Torrent> torrents, Optional<Path> incompleteDir, List<Path> libraryRoots) {
        Set<FileId> library = libraryIndex(libraryRoots);
        List<SnapshotTorrent> resolved = torrents.stream()
                .map(torrent -> new SnapshotTorrent(
                        torrent,
                        torrent.files().stream()
                                .filter(Torrent.File::wanted)
                                .map(file ->
                                        new WantedFile(file.name(), TorrentPaths.resolve(torrent, file, incompleteDir)))
                                .toList()))
                .toList();
        return new Snapshot(resolved, library);
    }

    /**
     * The library index: every regular file under the roots. Symlinks are neither followed nor indexed.
     *
     * @throws SnapshotException when a library root can't be read
     */
    public static Set<FileId> libraryIndex(List<Path> libraryRoots) {
        Set<FileId> index = new HashSet<>();
        for (Path root : libraryRoots) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        if (attributes.isRegularFile()) {
                            index.add(TorrentPaths.id(file, LinkOption.NOFOLLOW_LINKS)
                                    .orElseThrow(() -> new IOException(file + " can't be read")));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new SnapshotException(
                        "Library root " + root + " can't be read ("
                                + e.getClass().getSimpleName() + ")",
                        e);
            }
        }
        return index;
    }
}
