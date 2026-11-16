package io.github.ghostwritertje.seedkeeper.orphans;

import io.github.ghostwritertje.seedkeeper.groups.StatusText;
import io.github.ghostwritertje.seedkeeper.orphans.OrphansService.OrphansUnavailable;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** The Orphans view. */
@Path("/orphans")
public class OrphansPage {

    @CheckedTemplate
    static class Templates {

        /**
         * @param files            null when the orphans can't be listed
         * @param danglingSymlinks null when the orphans can't be listed
         * @param unavailable      why the orphans can't be listed right now; null when they can
         * @param unreadable       what could not be read and was skipped, as one line; null when everything was read
         */
        static native TemplateInstance orphans(
                @Nullable Listing files,
                @Nullable Listing danglingSymlinks,
                @Nullable String unavailable,
                @Nullable String unreadable);
    }

    /**
     * One of the two lists, as text ready to show.
     *
     * @param total     "Total of 2 files"
     * @param totalSize "2.0 KiB": what deleting them would free, so without the files that have a library link
     */
    public record Listing(List<Row> rows, String total, String totalSize) {}

    /**
     * One orphan, as text ready to show.
     *
     * @param size "1.5 KiB"
     * @param age         how long ago it was last modified: "3d 2h"
     * @param libraryLink "Yes" when a hardlink of the file is under a library root; "" otherwise
     */
    public record Row(String path, String size, String age, String libraryLink) {}

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
            return Templates.orphans(
                    listing(found.files(), "file", "files", now),
                    listing(found.danglingSymlinks(), "dangling symlink", "dangling symlinks", now),
                    null,
                    unreadable(found.unreadable()));
        } catch (OrphansUnavailable e) {
            return Templates.orphans(null, null, e.getMessage(), null);
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

    private static Listing listing(List<Orphans.Orphan> orphans, String one, String many, Instant now) {
        List<Row> rows = orphans.stream()
                .map(orphan -> new Row(
                        orphan.path().toString(),
                        size(orphan.size()),
                        age(Duration.between(orphan.lastModified(), now)),
                        orphan.libraryLink() ? "Yes" : ""))
                .toList();
        return new Listing(
                rows,
                "Total of " + rows.size() + " " + (rows.size() == 1 ? one : many),
                size(orphans.stream()
                        .filter(orphan -> !orphan.libraryLink())
                        .mapToLong(Orphans.Orphan::size)
                        .sum()));
    }

    /** Bytes in the largest binary unit that keeps the number at 1 or more: "512 B", "1.5 KiB", "3.2 GiB". */
    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /** An age in its two largest units: "3d 2h". A file modified in the future has no age yet: "0m". */
    private static String age(Duration age) {
        return StatusText.duration(age.isNegative() ? Duration.ZERO : age);
    }
}
