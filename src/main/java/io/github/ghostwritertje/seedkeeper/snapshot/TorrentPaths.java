package io.github.ghostwritertje.seedkeeper.snapshot;

import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Where a torrent's files can be on disk (spec §3.1), in one place for the snapshot, the orphans and deletion. */
public final class TorrentPaths {

    private static final String DEV_AND_INO = "unix:dev,ino";

    private TorrentPaths() {
    }

    /**
     * The directories a torrent's files can be in: its download dir, then the incomplete dir.
     *
     * @param incompleteDir Transmission's incomplete dir; empty when it is not enabled
     */
    public static List<Path> dirs(Torrent torrent, Optional<Path> incompleteDir) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(Path.of(torrent.downloadDir()));
        incompleteDir.ifPresent(dirs::add);
        return dirs;
    }

    /** The paths a file can be at in one of the {@link #dirs}: as it is named, then with {@code .part}; normalized. */
    public static List<Path> candidatesIn(Path dir, Torrent.File file) {
        return List.of(dir.resolve(file.name()).normalize(), dir.resolve(file.name() + ".part").normalize());
    }

    /** The paths a torrent's file can be at, in the order of spec §3.1. The first that exists is the file. */
    public static List<Path> candidates(Torrent torrent, Torrent.File file, Optional<Path> incompleteDir) {
        return dirs(torrent, incompleteDir).stream().flatMap(dir -> candidatesIn(dir, file).stream()).toList();
    }

    /** The file the first candidate path that exists leads to, following symlinks; empty when there is none. */
    public static Optional<FileId> resolve(Torrent torrent, Torrent.File file, Optional<Path> incompleteDir) {
        return candidates(torrent, file, incompleteDir).stream().flatMap(path -> id(path).stream()).findFirst();
    }

    /** Empty when the path doesn't exist, is a dangling symlink or can't be read. */
    public static Optional<FileId> id(Path path, LinkOption... options) {
        try {
            Map<String, Object> attributes = Files.readAttributes(path, DEV_AND_INO, options);
            return Optional.of(new FileId((Long) attributes.get("dev"), (Long) attributes.get("ino")));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
