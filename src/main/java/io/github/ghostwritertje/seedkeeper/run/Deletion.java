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
 * <p>Only the paths of the torrents that left are ever deleted: never what a symlink leads to. A path stays when a
 * remaining torrent still uses it: a symlink by its exact path, a regular file by the file it is, whatever its path.
 * Every file of a remaining torrent counts, also one that is not wanted.
 *
 * <p>The guard: a path is deleted only when it really is (after following the symlinks in its directories) inside
 * the downloads root or the incomplete dir, and not under a library root. Anything else is refused and reported.
 */
public final class Deletion {

    /**
     * @param files      the files and symlinks that were deleted, in the order they went
     * @param spaceFreed the size of every deleted file that was the last link of its data, in bytes
     * @param refused    the paths that were due but not deleted: outside the downloads root and incomplete dir, under
     *                   a library root, or the filesystem said no
     */
    public record Deleted(List<Path> files, long spaceFreed, List<Path> refused) {

        public Deleted {
            files = List.copyOf(files);
            refused = List.copyOf(refused);
        }

        static final Deleted NOTHING = new Deleted(List.of(), 0, List.of());
    }

    private final Path downloadsRoot;
    private final List<Path> libraryRoots;

    public Deletion(Path downloadsRoot, List<Path> libraryRoots) {
        this.downloadsRoot = real(downloadsRoot);
        this.libraryRoots = libraryRoots.stream().map(Deletion::real).toList();
    }

    /**
     * @param removed       the torrents that have left Transmission
     * @param remaining     every torrent Transmission still has, read after the removal
     * @param incompleteDir Transmission's incomplete dir; empty when it is not enabled
     */
    public Deleted delete(Collection<Torrent> removed, Collection<Torrent> remaining, Optional<Path> incompleteDir) {
        Set<Path> keptPaths = new HashSet<>();
        Set<FileId> keptFiles = new HashSet<>();
        for (Torrent torrent : remaining) {
            for (Torrent.File file : torrent.files()) {
                keptPaths.addAll(TorrentPaths.candidates(torrent, file, incompleteDir));
                TorrentPaths.resolve(torrent, file, incompleteDir).ifPresent(keptFiles::add);
            }
        }
        List<Path> allowedRoots = new ArrayList<>();
        allowedRoots.add(downloadsRoot);
        incompleteDir.map(Deletion::real).ifPresent(allowedRoots::add);

        List<Path> files = new ArrayList<>();
        List<Path> refused = new ArrayList<>();
        long spaceFreed = 0;
        for (Torrent torrent : removed) {
            for (Path dir : TorrentPaths.dirs(torrent, incompleteDir)) {
                for (Torrent.File file : torrent.files()) {
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
                        if (!allowed(path, allowedRoots)) {
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
                        deleteEmptyDirectories(path.getParent(), dir.normalize(), allowedRoots);
                    }
                }
            }
        }
        return new Deleted(files, spaceFreed, refused);
    }

    /** Walks up from {@code directory} and deletes what is empty; stops at {@code stop}, which always stays. */
    private void deleteEmptyDirectories(Path directory, Path stop, List<Path> allowedRoots) {
        for (Path current = directory; current != null && !current.equals(stop) && current.startsWith(stop);
                current = current.getParent()) {
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || !allowed(current, allowedRoots)) {
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
    private boolean allowed(Path path, List<Path> allowedRoots) {
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

    /** The path with every symlink followed, so that two ways to name one place compare equal. */
    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
