package io.github.ghostwritertje.seedkeeper.orphans;

import io.github.ghostwritertje.seedkeeper.orphans.OrphansService.OrphansUnavailable;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateData;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** The Orphans view. */
@Path("/orphans")
class OrphansPage {

    @CheckedTemplate
    static class Templates {

        /**
         * @param files            the orphan files but the system files; null when the orphans can't be listed
         * @param systemFiles      the orphan files that are OS or NAS metadata, shown only on request; null when
         *                         the orphans can't be listed
         * @param danglingSymlinks null when the orphans can't be listed
         * @param unavailable      why the orphans can't be listed right now; null when they can
         * @param unreadable       what could not be read and was skipped, as one line; null when everything was read
         */
        static native TemplateInstance orphans(
                @Nullable Listing files,
                @Nullable Listing systemFiles,
                @Nullable Listing danglingSymlinks,
                @Nullable String unavailable,
                @Nullable String unreadable);
    }

    /**
     * One list of orphans, grouped by the top-level directory under the downloads root they are in, as text ready to
     * show.
     *
     * @param groups       in path order; an orphan right under the downloads root is in the group of the root itself
     * @param count        "2 files"
     * @param totalSize    "2.0 KiB": what deleting them would free, so without the files that have a library link
     * @param libraryLinks whether an orphan has a library link, so the column is worth showing
     */
    // The {#orphanGroups} tag's parameters aren't type-checked, so Qute only generates value resolvers for this record,
    // Group and Row with @TemplateData; the native image has no reflection to fall back on.
    @TemplateData
    public record Listing(List<Group> groups, String count, String totalSize, boolean libraryLinks) {}

    /**
     * The orphans in one top-level directory.
     *
     * @param directory "/downloads/cross-seed"
     * @param summary   "2 files, 2.0 KiB": the size without the files that have a library link
     * @param rows      in the order of the orphans: by path
     */
    @TemplateData
    public record Group(String directory, String summary, List<Row> rows) {}

    /**
     * One orphan, as text ready to show.
     *
     * @param path        relative to the directory of its group
     * @param target      where a dangling symlink leads, as the link has it; "" for a file
     * @param size        "1.5 KiB"
     * @param age         how long ago it was last modified: "3d 2h"
     * @param libraryLink "Yes" when a hardlink of the file is under a library root; "" otherwise
     */
    @TemplateData
    public record Row(String path, String target, String size, String age, String libraryLink) {}

    private final OrphansService orphans;

    OrphansPage(OrphansService orphans) {
        this.orphans = orphans;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public TemplateInstance orphans() {
        try {
            Orphans found = orphans.orphans();
            Instant now = Instant.now();
            java.nio.file.Path root = found.downloadsRoot();
            List<Orphans.Orphan> files = found.files().stream()
                    .filter(orphan -> !orphan.systemFile())
                    .toList();
            List<Orphans.Orphan> systemFiles =
                    found.files().stream().filter(Orphans.Orphan::systemFile).toList();
            return Templates.orphans(
                    listing(root, files, "file", "files", now),
                    listing(root, systemFiles, "system file", "system files", now),
                    listing(root, found.danglingSymlinks(), "dangling symlink", "dangling symlinks", now),
                    null,
                    unreadable(found.unreadable()));
        } catch (OrphansUnavailable e) {
            return Templates.orphans(null, null, null, e.getMessage(), null);
        }
    }

    private static @Nullable String unreadable(List<java.nio.file.Path> paths) {
        if (paths.isEmpty()) {
            return null;
        }
        return paths.size() + (paths.size() == 1 ? " path" : " paths") + " under the downloads root could not be read"
                + " and " + (paths.size() == 1 ? "was" : "were") + " skipped, such as " + paths.getFirst()
                + ". What is in there is not listed.";
    }

    /**
     * The orphans as the view lists them, grouped by the top-level directory under the downloads root they are in.
     *
     * @param one  what one of them is called: "file"
     * @param many what more of them are called: "files"
     */
    static Listing listing(
            java.nio.file.Path downloadsRoot, List<Orphans.Orphan> orphans, String one, String many, Instant now) {
        Map<java.nio.file.Path, List<Orphans.Orphan>> byDirectory = new TreeMap<>();
        for (Orphans.Orphan orphan : orphans) {
            byDirectory
                    .computeIfAbsent(topLevelDirectory(downloadsRoot, orphan.path()), directory -> new ArrayList<>())
                    .add(orphan);
        }
        List<Group> groups = byDirectory.entrySet().stream()
                .map(entry -> new Group(
                        entry.getKey().toString(),
                        count(entry.getValue().size(), one, many) + ", " + StatusText.size(freed(entry.getValue())),
                        entry.getValue().stream()
                                .map(orphan -> row(entry.getKey(), orphan, now))
                                .toList()))
                .toList();
        return new Listing(
                groups,
                count(orphans.size(), one, many),
                StatusText.size(freed(orphans)),
                orphans.stream().anyMatch(Orphans.Orphan::libraryLink));
    }

    /** The directory right under the downloads root that the path is in; the root itself for a path right in it. */
    private static java.nio.file.Path topLevelDirectory(java.nio.file.Path downloadsRoot, java.nio.file.Path path) {
        java.nio.file.Path relative = downloadsRoot.relativize(path);
        return relative.getNameCount() > 1 ? downloadsRoot.resolve(relative.getName(0)) : downloadsRoot;
    }

    private static Row row(java.nio.file.Path directory, Orphans.Orphan orphan, Instant now) {
        java.nio.file.Path target = orphan.target();
        return new Row(
                directory.relativize(orphan.path()).toString(),
                target == null ? "" : target.toString(),
                StatusText.size(orphan.size()),
                age(Duration.between(orphan.lastModified(), now)),
                orphan.libraryLink() ? "Yes" : "");
    }

    private static String count(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    /** What deleting them would free: the bytes of those without a library link. */
    private static long freed(List<Orphans.Orphan> orphans) {
        return orphans.stream()
                .filter(orphan -> !orphan.libraryLink())
                .mapToLong(Orphans.Orphan::size)
                .sum();
    }

    /** An age in its two largest units: "3d 2h". A file modified in the future has no age yet: "0m". */
    private static String age(Duration age) {
        return StatusText.duration(age.isNegative() ? Duration.ZERO : age);
    }
}
