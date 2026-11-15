package io.github.ghostwritertje.seedkeeper.snapshot;

import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * The cross-seed groups (spec §3.3): two torrents are in one group when any of their resolved wanted files are
     * the same file, directly or through another member. A torrent with no resolvable file is a group of its own.
     * Groups are in the order of their first member, members in snapshot order.
     */
    public List<List<SnapshotTorrent>> groups() {
        // Union-find over the torrents' positions; the root of a group is its first member.
        int[] parent = new int[torrents.size()];
        Map<FileId, Integer> firstWithFile = new HashMap<>();
        for (int i = 0; i < torrents.size(); i++) {
            parent[i] = i;
            for (WantedFile file : torrents.get(i).wantedFiles()) {
                if (file.id().isPresent()) {
                    Integer other = firstWithFile.putIfAbsent(file.id().get(), i);
                    if (other != null) {
                        int a = root(parent, other);
                        int b = root(parent, i);
                        parent[Math.max(a, b)] = Math.min(a, b);
                    }
                }
            }
        }
        Map<Integer, List<SnapshotTorrent>> groups = new LinkedHashMap<>();
        for (int i = 0; i < torrents.size(); i++) {
            groups.computeIfAbsent(root(parent, i), root -> new ArrayList<>()).add(torrents.get(i));
        }
        return groups.values().stream().map(List::copyOf).toList();
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** True when the file shares its inode with a file under a library root. */
    public boolean hasLibraryLink(WantedFile file) {
        return file.id().filter(library::contains).isPresent();
    }
}
