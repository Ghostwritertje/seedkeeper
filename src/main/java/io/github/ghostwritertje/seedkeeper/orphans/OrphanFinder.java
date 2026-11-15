package io.github.ghostwritertje.seedkeeper.orphans;

import io.github.ghostwritertje.seedkeeper.orphans.Orphans.Orphan;
import io.github.ghostwritertje.seedkeeper.snapshot.FileId;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the orphans: what is under the downloads root that no torrent references. It only reads.
 *
 * <p>What a torrent references follows the rules of deletion (spec §4): a regular file by the file it is, whatever
 * its path, so a hardlink of a torrent's file and the file a torrent's symlink leads to are not orphans; a symlink
 * by its exact path. Every file of a torrent counts, also one that is not wanted: Transmission still keeps the
 * pieces it shares with its neighbours in it.
 */
public final class OrphanFinder {

    private static final String DEV_AND_INO = "unix:dev,ino";

    private OrphanFinder() {
    }

    /**
     * @param incompleteDir Transmission's incomplete dir; empty when it is not enabled
     * @throws OrphansException with one reason when the downloads root can't be read
     */
    public static Orphans find(Path downloadsRoot, List<Torrent> torrents, Optional<Path> incompleteDir) {
        Set<Path> referencedPaths = new HashSet<>();
        Set<FileId> referencedFiles = new HashSet<>();
        for (Torrent torrent : torrents) {
            for (Torrent.File file : torrent.files()) {
                List<Path> candidates = candidates(torrent, file, incompleteDir);
                referencedPaths.addAll(candidates);
                candidates.stream().flatMap(path -> id(path).stream()).findFirst().ifPresent(referencedFiles::add);
            }
        }

        List<Orphan> files = new ArrayList<>();
        List<Orphan> danglingSymlinks = new ArrayList<>();
        try {
            Files.walkFileTree(downloadsRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    Orphan orphan = new Orphan(path, attributes.size(), attributes.lastModifiedTime().toInstant());
                    if (attributes.isSymbolicLink()) {
                        if (Files.notExists(path) && !referencedPaths.contains(path.normalize())) {
                            danglingSymlinks.add(orphan);
                        }
                    } else if (attributes.isRegularFile()
                            && id(path, LinkOption.NOFOLLOW_LINKS).filter(referencedFiles::contains).isEmpty()) {
                        files.add(orphan);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new OrphansException(
                    "Downloads root " + downloadsRoot + " can't be read (" + e.getClass().getSimpleName() + ")", e);
        }
        files.sort(Comparator.comparing(Orphan::path));
        danglingSymlinks.sort(Comparator.comparing(Orphan::path));
        return new Orphans(files, danglingSymlinks);
    }

    /** The paths a torrent's file can be at, in the order of spec §3.1. The first that exists is the file. */
    private static List<Path> candidates(Torrent torrent, Torrent.File file, Optional<Path> incompleteDir) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(Path.of(torrent.downloadDir()));
        incompleteDir.ifPresent(dirs::add);
        List<Path> candidates = new ArrayList<>();
        for (Path dir : dirs) {
            candidates.add(dir.resolve(file.name()).normalize());
            candidates.add(dir.resolve(file.name() + ".part").normalize());
        }
        return candidates;
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
}
