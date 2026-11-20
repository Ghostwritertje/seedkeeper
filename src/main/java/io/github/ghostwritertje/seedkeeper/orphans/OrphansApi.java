package io.github.ghostwritertje.seedkeeper.orphans;

import io.github.ghostwritertje.seedkeeper.orphans.OrphansService.OrphansUnavailable;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** {@code GET /api/v1/orphans}: the same data as the Orphans view. */
@Path("/api/v1/orphans")
class OrphansApi {

    /**
     * The orphans under the downloads root.
     *
     * @param totalSizeBytes what deleting the orphan files would free: the bytes of those without a library link
     * @param unreadable     the paths under the downloads root that could not be read and were skipped
     */
    @RegisterForReflection
    public record Body(
            List<Orphan> files, List<Orphan> danglingSymlinks, long totalSizeBytes, List<String> unreadable) {}

    /**
     * One orphan file or dangling symlink.
     *
     * @param sizeBytes    of a symlink, the size of the link itself
     * @param lastModified ISO-8601, in UTC, to the second; of a symlink, of the link itself
     * @param libraryLink  whether a hardlink of the file is under a library root; never true of a symlink
     * @param systemFile   whether it is metadata the OS or the NAS writes: {@code .DS_Store}, {@code ._*},
     *                     {@code Thumbs.db} or anything in a Synology {@code @eaDir}
     * @param target       where a dangling symlink leads, as the link has it; null for a file
     */
    @RegisterForReflection
    public record Orphan(
            String path,
            long sizeBytes,
            String lastModified,
            boolean libraryLink,
            boolean systemFile,
            @Nullable String target) {}

    /** The body of a 503: why the orphans can't be listed right now. */
    @RegisterForReflection
    public record Unavailable(String reason) {}

    private final OrphansService orphans;

    OrphansApi(OrphansService orphans) {
        this.orphans = orphans;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response orphans() {
        try {
            Orphans found = orphans.orphans();
            return Response.ok(new Body(
                            found.files().stream().map(OrphansApi::orphan).toList(),
                            found.danglingSymlinks().stream()
                                    .map(OrphansApi::orphan)
                                    .toList(),
                            found.totalSize(),
                            found.unreadable().stream().map(Object::toString).toList()))
                    .build();
        } catch (OrphansUnavailable e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new Unavailable(e.getMessage()))
                    .build();
        }
    }

    private static Orphan orphan(Orphans.Orphan orphan) {
        java.nio.file.Path target = orphan.target();
        return new Orphan(
                orphan.path().toString(),
                orphan.size(),
                orphan.lastModified().truncatedTo(ChronoUnit.SECONDS).toString(),
                orphan.libraryLink(),
                orphan.systemFile(),
                target == null ? null : target.toString());
    }
}
