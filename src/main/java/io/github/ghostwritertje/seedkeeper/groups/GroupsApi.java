package io.github.ghostwritertje.seedkeeper.groups;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.decide.Tracker;
import io.github.ghostwritertje.seedkeeper.decide.TrackerError;
import io.github.ghostwritertje.seedkeeper.groups.GroupsFilter.UnknownFilter;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService.GroupsUnavailable;
import io.github.ghostwritertje.seedkeeper.history.History;
import io.github.ghostwritertje.seedkeeper.history.History.ReasonsSince;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** {@code GET /api/v1/groups}: the same data as the Groups view. */
@Path("/api/v1/groups")
class GroupsApi {

    /**
     * One cross-seed group and its verdict.
     *
     * @param verdict      {@code remove}, {@code left-alone} or {@code keep}
     * @param reason       the kebab-case code of the reason that decided the verdict
     * @param secondaryReasons the codes of every other reason that holds too, in the order of precedence
     * @param libraryLinks how many wanted files of the members have a library link
     * @param missingFiles how many wanted files of finished members are not on disk
     * @param files        how many wanted files the members have
     * @param forecast     null unless the reason is {@code minimum-not-reached}, or {@code keep-override} with an
     *                     end date
     * @param reasonSince  the start of the first of the unbroken row of successful runs, up to the last one, that
     *                     gave the group this reason, as an ISO 8601 date and time with the offset of the configured
     *                     time zone; null when the last successful run didn't see the group with this reason
     */
    @RegisterForReflection
    public record Group(
            String verdict,
            String reason,
            @Nullable String reasonSince,
            List<String> secondaryReasons,
            int libraryLinks,
            int missingFiles,
            int files,
            @Nullable Forecast forecast,
            List<Member> members) {}

    /**
     * When what keeps the group is expected to end.
     *
     * @param date         when every member is expected to have met its minimum, as an ISO 8601 date and time with
     *                     the offset of the configured time zone; null when the clock is stopped
     * @param clockStopped true when the member that sets the date is paused
     * @param until        the end date (inclusive) of the keep override that keeps the group, as an ISO 8601 date;
     *                     null unless the reason is {@code keep-override}
     */
    @RegisterForReflection
    public record Forecast(
            @Nullable String date,
            boolean clockStopped,
            @Nullable String until) {}

    /**
     * One torrent of the group.
     *
     * @param tracker                null when the torrent is unmanaged
     * @param minimumSeedTimeSeconds null when the torrent is unmanaged
     * @param trackerError           {@code unregistered}, {@code tracker-error} or {@code tracker-warning}; null
     *                               when the tracker reports nothing wrong
     * @param trackerMessage         what the tracker says, as Transmission reports it; null when there is no
     *                               tracker error
     * @param announceHosts          the hosts of the torrent's announce URLs, once each, in announce order; empty when
     *                               it has none. Only the hosts: an announce URL may hold a passkey
     */
    @RegisterForReflection
    public record Member(
            String hash,
            String name,
            @Nullable String tracker,
            String status,
            long seedTimeSeconds,
            @Nullable Long minimumSeedTimeSeconds,
            @Nullable String trackerError,
            @Nullable String trackerMessage,
            List<String> announceHosts) {}

    /** The body of a 503: why there are no verdicts right now. Of a 400: what is wrong with the filter. */
    @RegisterForReflection
    public record Unavailable(String reason) {}

    private final GroupsService groups;
    private final History history;
    private final ZoneId timeZone;

    GroupsApi(GroupsService groups, History history, SeedkeeperConfig config) {
        this.groups = groups;
        this.history = history;
        this.timeZone = config.timeZone();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response groups(
            @QueryParam("verdict") @Nullable String verdict, @QueryParam("reason") @Nullable String reason) {
        try {
            GroupsFilter filter = GroupsFilter.parse(verdict, reason);
            List<GroupVerdict> verdicts =
                    groups.groups().stream().filter(filter::matches).toList();
            ReasonsSince since = history.reasonsSince();
            return Response.ok(
                            verdicts.stream().map(group -> group(group, since)).toList())
                    .build();
        } catch (UnknownFilter e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new Unavailable(e.getMessage()))
                    .build();
        } catch (GroupsUnavailable e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new Unavailable(e.getMessage()))
                    .build();
        }
    }

    private Group group(GroupVerdict group, ReasonsSince since) {
        return new Group(
                group.verdict().code(),
                group.reason().code(),
                since.of(group)
                        .map(moment -> moment.atZone(timeZone)
                                .truncatedTo(ChronoUnit.SECONDS)
                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        .orElse(null),
                group.secondaryReasons().stream().map(Reason::code).toList(),
                group.libraryLinks(),
                group.missingFiles(),
                group.files(),
                group.forecast().map(GroupsApi::forecast).orElse(null),
                group.members().stream().map(GroupsApi::member).toList());
    }

    private static Forecast forecast(io.github.ghostwritertje.seedkeeper.decide.Forecast forecast) {
        return new Forecast(
                forecast.date()
                        .map(date ->
                                date.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        .orElse(null),
                forecast.clockStopped(),
                forecast.until().map(Object::toString).orElse(null));
    }

    private static Member member(GroupVerdict.Member member) {
        return new Member(
                member.hash(),
                member.name(),
                member.tracker().map(Tracker::name).orElse(null),
                StatusText.code(member.status()),
                member.seedTime().toSeconds(),
                member.tracker()
                        .map(tracker -> tracker.minimumSeedTime().toSeconds())
                        .orElse(null),
                member.trackerError().map(error -> error.kind().code()).orElse(null),
                member.trackerError().map(TrackerError::message).orElse(null),
                member.announceHosts());
    }
}
