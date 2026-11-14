package io.github.ghostwritertje.seedkeeper.snapshot;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads the filesystem part of a snapshot (spec §3.1): where each wanted file is, and the library index. */
public final class SnapshotBuilder {

    private static final String DEV_AND_INO = "unix:dev,ino";

    private SnapshotBuilder() {
    }

    /**
     * @param incompleteDir Transmission's incomplete dir; empty when it is not enabled
     * @throws SnapshotException when a library root can't be read: without it every library link would seem gone
     */
    public static Snapshot build(List<Torrent> torrents, Optional<Path> incompleteDir, List<Path> libraryRoots) {
        Set<FileId> library = libraryIndex(libraryRoots);
        List<SnapshotTorrent> resolved = torrents.stream()
                .map(torrent -> new SnapshotTorrent(torrent, torrent.files().stream()
                        .filter(Torrent.File::wanted)
                        .map(file -> new WantedFile(file.name(), resolve(torrent, file, incompleteDir)))
                        .toList()))
                .toList();
        return new Snapshot(resolved, library);
    }

    /** The file the first candidate path that exists leads to, following symlinks. */
    private static Optional<FileId> resolve(Torrent torrent, Torrent.File file, Optional<Path> incompleteDir) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(Path.of(torrent.downloadDir()));
        incompleteDir.ifPresent(dirs::add);
        for (Path dir : dirs) {
            for (String name : List.of(file.name(), file.name() + ".part")) {
                Optional<FileId> id = id(dir.resolve(name));
                if (id.isPresent()) {
                    return id;
                }
            }
        }
        return Optional.empty();
    }

    /** Empty when the path doesn't exist, is a dangling symlink or can't be read. */
    private static Optional<FileId> id(Path path, LinkOption... options) {
        try {
            Map<String, Object> attributes = Files.readAttributes(path, DEV_AND_INO, options);
            return Optional.of(new FileId((Long) attributes.get("dev"), (Long) attributes.get("ino")));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Every regular file under the roots. Symlinks are neither followed nor indexed. */
    private static Set<FileId> libraryIndex(List<Path> libraryRoots) {
        Set<FileId> index = new HashSet<>();
        for (Path root : libraryRoots) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        if (attributes.isRegularFile()) {
                            Map<String, Object> id = Files.readAttributes(file, DEV_AND_INO,
                                    LinkOption.NOFOLLOW_LINKS);
                            index.add(new FileId((Long) id.get("dev"), (Long) id.get("ino")));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new SnapshotException(
                        "Library root " + root + " can't be read (" + e.getClass().getSimpleName() + ")", e);
            }
        }
        return index;
    }
}
