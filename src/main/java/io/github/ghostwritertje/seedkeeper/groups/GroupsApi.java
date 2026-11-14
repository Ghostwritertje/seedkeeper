package io.github.ghostwritertje.seedkeeper.groups;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Tracker;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService.GroupsUnavailable;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/** {@code GET /api/v1/groups}: the same data as the Groups view. */
@Path("/api/v1/groups")
public class GroupsApi {

    /**
     * @param verdict      {@code remove}, {@code left-alone} or {@code keep}
     * @param reason       the kebab-case code of the reason that decided the verdict
     * @param libraryLinks how many wanted files of the members have a library link
     * @param files        how many wanted files the members have
     */
    @RegisterForReflection
    public record Group(String verdict, String reason, int libraryLinks, int files, List<Member> members) {
    }

    /**
     * @param tracker                null when the torrent is unmanaged
     * @param minimumSeedTimeSeconds null when the torrent is unmanaged
     */
    @RegisterForReflection
    public record Member(String hash, String name, String tracker, String status, long seedTimeSeconds,
            Long minimumSeedTimeSeconds) {
    }

    /** The body of a 503: why there are no verdicts right now. */
    @RegisterForReflection
    public record Unavailable(String reason) {
    }

    private final GroupsService groups;

    GroupsApi(GroupsService groups) {
        this.groups = groups;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response groups() {
        try {
            return Response.ok(groups.groups().stream().map(GroupsApi::group).toList()).build();
        } catch (GroupsUnavailable e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(new Unavailable(e.getMessage()))
                    .build();
        }
    }

    private static Group group(GroupVerdict group) {
        return new Group(group.verdict().code(), group.reason().code(), group.libraryLinks(), group.files(),
                group.members().stream().map(GroupsApi::member).toList());
    }

    private static Member member(GroupVerdict.Member member) {
        return new Member(member.hash(), member.name(), member.tracker().map(Tracker::name).orElse(null),
                StatusText.code(member.status()), member.seedTime().toSeconds(),
                member.tracker().map(tracker -> tracker.minimumSeedTime().toSeconds()).orElse(null));
    }
}
