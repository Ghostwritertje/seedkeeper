package io.github.ghostwritertje.seedkeeper.orphans;

import io.github.ghostwritertje.seedkeeper.orphans.Orphans.Orphan;
import io.github.ghostwritertje.seedkeeper.snapshot.FileId;
import io.github.ghostwritertje.seedkeeper.snapshot.SnapshotBuilder;
import io.github.ghostwritertje.seedkeeper.snapshot.SnapshotException;
import io.github.ghostwritertje.seedkeeper.snapshot.TorrentPaths;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the orphans: what is under the downloads root, outside every library root, that no torrent references. It
 * only reads. An orphan file with a library link is still an orphan, marked as such.
 *
 * <p>What a torrent references follows the rules of deletion (spec §4): a regular file by the file it is, whatever
 * its path, so a hardlink of a torrent's file and the file a torrent's symlink leads to are not orphans; a symlink
 * by its exact path. Every file of a torrent counts, also one that is not wanted: Transmission still keeps the
 * pieces it shares with its neighbours in it.
 */
public final class OrphanFinder {

    private OrphanFinder() {
    }

    /**
     * @param incompleteDir Transmission's incomplete dir; empty when it is not enabled
     * @throws OrphansException with one reason when the downloads root or a library root can't be read: without a
     *                          library root the library links, and so the total, would be wrong
     */
    public static Orphans find(Path downloadsRoot, List<Path> libraryRoots, List<Torrent> torrents,
            Optional<Path> incompleteDir) {
        Set<Path> referencedPaths = new HashSet<>();
        Set<FileId> referencedFiles = new HashSet<>();
        for (Torrent torrent : torrents) {
            for (Torrent.File file : torrent.files()) {
                referencedPaths.addAll(TorrentPaths.candidates(torrent, file, incompleteDir));
                TorrentPaths.resolve(torrent, file, incompleteDir).ifPresent(referencedFiles::add);
            }
        }

        Set<FileId> library;
        try {
            library = SnapshotBuilder.libraryIndex(libraryRoots);
        } catch (SnapshotException e) {
            throw new OrphansException(e.getMessage(), e);
        }
        Set<FileId> libraryRootDirectories = new HashSet<>();
        for (Path libraryRoot : libraryRoots) {
            TorrentPaths.id(libraryRoot).ifPresent(libraryRootDirectories::add);
        }

        List<Orphan> files = new ArrayList<>();
        List<Orphan> danglingSymlinks = new ArrayList<>();
        List<Path> unreadable = new ArrayList<>();
        try {
            Files.walkFileTree(downloadsRoot, new SimpleFileVisitor<>() {
                /** What is under a library root is the media library's, never an orphan. */
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    Optional<FileId> id = TorrentPaths.id(directory, LinkOption.NOFOLLOW_LINKS);
                    if (id.isPresent() && libraryRootDirectories.contains(id.get())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    Instant lastModified = attributes.lastModifiedTime().toInstant();
                    if (attributes.isSymbolicLink()) {
                        if (Files.notExists(path) && !referencedPaths.contains(path.normalize())) {
                            danglingSymlinks.add(new Orphan(path, attributes.size(), lastModified, false));
                        }
                    } else if (attributes.isRegularFile()) {
                        Optional<FileId> id = TorrentPaths.id(path, LinkOption.NOFOLLOW_LINKS);
                        if (id.filter(referencedFiles::contains).isEmpty()) {
                            files.add(new Orphan(path, attributes.size(), lastModified,
                                    id.filter(library::contains).isPresent()));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                /** One file or directory that can't be read doesn't hide the rest: it is skipped and counted. */
                @Override
                public FileVisitResult visitFileFailed(Path path, IOException e) throws IOException {
                    if (path.equals(downloadsRoot)) {
                        throw e;
                    }
                    unreadable.add(path);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new OrphansException(
                    "Downloads root " + downloadsRoot + " can't be read (" + e.getClass().getSimpleName() + ")", e);
        }
        files.sort(Comparator.comparing(Orphan::path));
        danglingSymlinks.sort(Comparator.comparing(Orphan::path));
        unreadable.sort(Comparator.naturalOrder());
        return new Orphans(files, danglingSymlinks, unreadable);
    }
}
