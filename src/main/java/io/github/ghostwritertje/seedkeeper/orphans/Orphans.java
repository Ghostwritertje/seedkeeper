package io.github.ghostwritertje.seedkeeper.orphans;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * What is under the downloads root that no torrent references. Shown, never deleted.
 *
 * @param files            regular files no torrent resolves to, by path
 * @param danglingSymlinks symlinks that lead nowhere and that no torrent has as a path, by path
 * @param unreadable       files and directories under the downloads root that could not be read, and were skipped,
 *                         by path
 */
public record Orphans(List<Orphan> files, List<Orphan> danglingSymlinks, List<Path> unreadable) {

    public Orphans {
        files = List.copyOf(files);
        danglingSymlinks = List.copyOf(danglingSymlinks);
        unreadable = List.copyOf(unreadable);
    }

    /**
     * @param size         in bytes; of a symlink, the size of the link itself
     * @param lastModified of a symlink, of the link itself
     */
    public record Orphan(Path path, long size, Instant lastModified) {
    }

    /** The bytes the orphan files take. A dangling symlink takes none worth counting. */
    public long totalSize() {
        return files.stream().mapToLong(Orphan::size).sum();
    }
}
