package io.github.ghostwritertje.seedkeeper.run;

import io.github.ghostwritertje.seedkeeper.snapshot.FileId;
import io.github.ghostwritertje.seedkeeper.snapshot.TorrentPaths;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Deletes what torrents that have left Transmission leave behind on disk (spec §4, steps 3 and 4).
 *
 * <p>Only the paths of the wanted files of the torrents that left are ever deleted: never what a symlink leads to. A
 * path stays when a remaining torrent still uses it: a symlink by its exact path, a regular file by the file it is,
 * whatever its path. Every file of a remaining torrent counts, also one that is not wanted.
 *
 * <p>The guard: a path is deleted only when it really is (after following the symlinks in its directories) inside
 * the downloads root or the incomplete dir, and not under a library root. Anything else is refused and reported.
 * The roots are looked up on every deletion, as they are then: while the downloads root or a library root can't be
 * found, every path is refused. The filesystem root {@code /} is never a place to delete in.
 */
public final class Deletion {

    /**
     * @param files      the files and symlinks that were deleted, in the order they went
     * @param spaceFreed the size of every deleted file that was the last link of its data, in bytes
     * @param refused    the paths that were due but not deleted: outside the downloads root and incomplete dir, under
     *                   a library root, while a root could not be found, or the filesystem said no
     */
    public record Deleted(List<Path> files, long spaceFreed, List<Path> refused) {

        public Deleted {
            files = List.copyOf(files);
            refused = List.copyOf(refused);
        }

        static final Deleted NOTHING = new Deleted(List.of(), 0, List.of());
    }

    /** The downloads root or a library root can't be used. The message says which and why, fit to show as it is. */
    public static class RootsUnavailable extends RuntimeException {

        RootsUnavailable(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    /** The roots as they really are right now. */
    private record Roots(Path downloadsRoot, List<Path> libraryRoots) {
    }

    private final Path downloadsRoot;
    private final List<Path> libraryRoots;

    public Deletion(Path downloadsRoot, List<Path> libraryRoots) {
        this.downloadsRoot = downloadsRoot;
        this.libraryRoots = List.copyOf(libraryRoots);
    }

    /**
     * Looks the roots up as they are now.
     *
     * @throws RootsUnavailable when the downloads root or a library root can't be found, or the downloads root is the
     *                          filesystem root
     */
    public void checkRoots() {
        roots();
    }

    /**
     * @param removed       the torrents that have left Transmission
     * @param remaining     every torrent Transmission still has, read after the removal
     * @param incompleteDir Transmission's incomplete dir; empty when it is not enabled
     */
    public Deleted delete(Collection<Torrent> removed, Collection<Torrent> remaining, Optional<Path> incompleteDir) {
        Roots roots;
        try {
            roots = roots();
        } catch (RootsUnavailable e) {
            // Without the roots there is no telling what is safe to delete: everything that is due is refused.
            roots = null;
        }
        Set<Path> keptPaths = new HashSet<>();
        Set<FileId> keptFiles = new HashSet<>();
        for (Torrent torrent : remaining) {
            for (Torrent.File file : torrent.files()) {
                keptPaths.addAll(TorrentPaths.candidates(torrent, file, incompleteDir));
                TorrentPaths.resolve(torrent, file, incompleteDir).ifPresent(keptFiles::add);
            }
        }
        List<Path> allowedRoots = new ArrayList<>();
        List<Path> libraries = roots == null ? List.of() : roots.libraryRoots();
        if (roots != null) {
            allowedRoots.add(roots.downloadsRoot());
            incompleteDir.flatMap(Deletion::real).filter(dir -> !isFilesystemRoot(dir)).ifPresent(allowedRoots::add);
        }

        List<Path> files = new ArrayList<>();
        List<Path> refused = new ArrayList<>();
        long spaceFreed = 0;
        for (Torrent torrent : removed) {
            for (Path dir : TorrentPaths.dirs(torrent, incompleteDir)) {
                for (Torrent.File file : torrent.files()) {
                    if (!file.wanted()) {
                        // A deselected file is no file of the torrent: whatever is at its path is not ours.
                        continue;
                    }
                    for (Path path : TorrentPaths.candidatesIn(dir, file)) {
                        BasicFileAttributes attributes = attributes(path);
                        if (attributes == null || keptPaths.contains(path)) {
                            continue;
                        }
                        boolean regularFile = attributes.isRegularFile();
                        if (regularFile) {
                            Optional<FileId> id = TorrentPaths.id(path, LinkOption.NOFOLLOW_LINKS);
                            if (id.isEmpty() || keptFiles.contains(id.get())) {
                                continue;
                            }
                        } else if (!attributes.isSymbolicLink()) {
                            continue;
                        }
                        if (roots == null || !allowed(path, allowedRoots, libraries)) {
                            refused.add(path);
                            continue;
                        }
                        try {
                            boolean lastLink = regularFile
                                    && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS))
                                            .intValue() == 1;
                            Files.delete(path);
                            files.add(path);
                            if (lastLink) {
                                spaceFreed += attributes.size();
                            }
                        } catch (IOException e) {
                            refused.add(path);
                            continue;
                        }
                        deleteEmptyDirectories(path.getParent(), dir.normalize(), allowedRoots, libraries);
                    }
                }
            }
        }
        return new Deleted(files, spaceFreed, refused);
    }

    private Roots roots() {
        Path downloads = real(downloadsRoot).orElseThrow(() -> new RootsUnavailable("The downloads root "
                + downloadsRoot + " (seedkeeper.downloads-root) can't be found", null));
        if (isFilesystemRoot(downloads)) {
            throw new RootsUnavailable("The downloads root " + downloadsRoot + " (seedkeeper.downloads-root) is the"
                    + " filesystem root: seedkeeper deletes nothing there", null);
        }
        List<Path> libraries = new ArrayList<>();
        for (Path library : libraryRoots) {
            libraries.add(real(library).orElseThrow(() -> new RootsUnavailable("The library root " + library
                    + " (seedkeeper.library-roots) can't be found", null)));
        }
        return new Roots(downloads, libraries);
    }

    /** Walks up from {@code directory} and deletes what is empty; stops at {@code stop}, which always stays. */
    private static void deleteEmptyDirectories(Path directory, Path stop, List<Path> allowedRoots,
            List<Path> libraryRoots) {
        for (Path current = directory; current != null && !current.equals(stop) && current.startsWith(stop);
                current = current.getParent()) {
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    || !allowed(current, allowedRoots, libraryRoots)) {
                return;
            }
            try {
                Files.delete(current);
            } catch (IOException e) {
                // Not empty, or not ours to delete.
                return;
            }
        }
    }

    /**
     * The guard. Looks at where the path really is: the symlinks in its directories are followed, the path itself is
     * not, since it is the path that is deleted and not what it leads to.
     */
    private static boolean allowed(Path path, List<Path> allowedRoots, List<Path> libraryRoots) {
        Path real;
        try {
            real = path.toAbsolutePath().getParent().toRealPath().resolve(path.getFileName());
        } catch (IOException | RuntimeException e) {
            return false;
        }
        boolean inside = allowedRoots.stream().anyMatch(root -> real.startsWith(root) && !real.equals(root));
        boolean inLibrary = libraryRoots.stream().anyMatch(real::startsWith);
        return inside && !inLibrary;
    }

    /** Null when there is nothing at the path. */
    private static BasicFileAttributes attributes(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isFilesystemRoot(Path path) {
        return path.getParent() == null;
    }

    /** The path with every symlink followed, so that two ways to name one place compare equal; empty when not there. */
    private static Optional<Path> real(Path path) {
        try {
            return Optional.of(path.toRealPath());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
