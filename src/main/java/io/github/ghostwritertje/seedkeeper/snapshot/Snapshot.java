package io.github.ghostwritertje.seedkeeper.snapshot;

import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Everything a run decides on, read once (spec §3.1).
 *
 * @param torrents every torrent in Transmission, with where its wanted files are on disk
 * @param library  the library index: every regular file under the library roots
 */
public record Snapshot(List<SnapshotTorrent> torrents, Set<FileId> library) {

    public Snapshot {
        torrents = List.copyOf(torrents);
        library = Set.copyOf(library);
    }

    /**
     * @param wantedFiles the torrent's wanted files only; a file with {@code wanted = false} is not in here
     */
    public record SnapshotTorrent(Torrent torrent, List<WantedFile> wantedFiles) {

        public SnapshotTorrent {
            wantedFiles = List.copyOf(wantedFiles);
        }
    }

    /**
     * @param name path relative to the download dir
     * @param id   the file it resolves to, following symlinks; empty when none of the candidate paths exists
     */
    public record WantedFile(String name, Optional<FileId> id) {
    }

    /** True when the file shares its inode with a file under a library root. */
    public boolean hasLibraryLink(WantedFile file) {
        return file.id().filter(library::contains).isPresent();
    }
}
