package io.github.ghostwritertje.seedkeeper.overrides;

import io.github.ghostwritertje.seedkeeper.overrides.OverridesService.Listed;
import io.github.ghostwritertje.seedkeeper.overrides.OverridesService.OverridesUnavailable;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** {@code GET /api/v1/overrides}: the same data as the Overrides view. */
@Path("/api/v1/overrides")
public class OverridesApi {

    /**
     * @param until   the end date (inclusive, in the configured time zone) as an ISO 8601 date; null when it never
     *                ends
     * @param created ISO 8601 date and time with offset, as in the file; null when the file doesn't say
     * @param active  true when it keeps a group: neither expired nor stale
     * @param expired true when it is past its end date
     * @param stale   true when none of its hashes is a torrent any more
     * @param groups  every current group it matches, each as its members; empty when stale
     */
    @RegisterForReflection
    public record Override(String id, List<String> hashes, String note, String until, String created,
            boolean active, boolean expired, boolean stale, List<List<Member>> groups) {
    }

    @RegisterForReflection
    public record Member(String hash, String name) {
    }

    /** The body of a 503: why the overrides can't be listed right now. */
    @RegisterForReflection
    public record Unavailable(String reason) {
    }

    private final OverridesService overrides;

    OverridesApi(OverridesService overrides) {
        this.overrides = overrides;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response overrides() {
        try {
            return Response.ok(overrides.overrides().stream().map(OverridesApi::override).toList()).build();
        } catch (OverridesUnavailable e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(new Unavailable(e.getMessage()))
                    .build();
        }
    }

    private static Override override(Listed listed) {
        KeepOverride override = listed.override();
        return new Override(override.id(), override.hashes(), override.note(),
                override.until().map(Object::toString).orElse(null),
                override.created().map(DateTimeFormatter.ISO_OFFSET_DATE_TIME::format).orElse(null),
                listed.active(), listed.expired(), listed.stale(),
                listed.groups().stream()
                        .map(group -> group.stream().map(member -> new Member(member.hash(), member.name())).toList())
                        .toList());
    }
}
