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
     * One orphan file or dangling symlink.
     *
     * @param size         in bytes; of a symlink, the size of the link itself
     * @param lastModified of a symlink, of the link itself
     * @param libraryLink  whether a hardlink of the file is under a library root: deleting it would free nothing.
     *                     Never true of a symlink
     */
    public record Orphan(Path path, long size, Instant lastModified, boolean libraryLink) {}

    /**
     * What deleting the orphan files would free: the bytes of those without a library link. A dangling symlink takes
     * none worth counting.
     */
    public long totalSize() {
        return files.stream()
                .filter(orphan -> !orphan.libraryLink())
                .mapToLong(Orphan::size)
                .sum();
    }
}
