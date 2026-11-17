package io.github.ghostwritertje.seedkeeper.history;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.history.History.HistoryException;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedMember;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedRemoval;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedRun;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** {@code GET /api/v1/runs} and {@code GET /api/v1/removals}: the same data as the Activity view. */
@Path("/api/v1")
class HistoryApi {

    /**
     * One run.
     *
     * @param id              counts up: a later run has a higher id
     * @param started         ISO 8601, to the second, with the offset of the configured time zone
     * @param status          {@code succeeded} or {@code failed}
     * @param error           the one reason of a failed run; null otherwise
     * @param groupsRemoved   the removals carried out in full
     * @param torrentsRemoved the torrents removed from Transmission, also those of a removal that then went wrong
     */
    @RegisterForReflection
    public record Run(
            long id,
            String started,
            String ended,
            String status,
            @Nullable String error,
            int groupsRemoved,
            int torrentsRemoved,
            int filesDeleted,
            long spaceFreedBytes) {}

    /**
     * One removal a run carried out.
     *
     * @param runId        the {@code id} of the run that did it
     * @param started      when that run started
     * @param reason       the kebab-case code of the reason the torrents had to leave
     * @param error        what went wrong, and then no file was deleted; null otherwise
     * @param pathsRefused the paths that were due but that seedkeeper did not delete
     */
    @RegisterForReflection
    public record Removal(
            long runId,
            String started,
            String reason,
            @Nullable String error,
            long spaceFreedBytes,
            int filesDeleted,
            int pathsRefused,
            List<Member> members) {}

    /**
     * One torrent of the removal.
     *
     * @param tracker        null when the torrent was unmanaged
     * @param trackerMessage what the tracker said about the torrent; null when it said nothing
     * @param unfinished     true when the download was not complete
     * @param removed        false when the removal went wrong before Transmission removed this torrent
     */
    @RegisterForReflection
    public record Member(
            String hash,
            String name,
            @Nullable String tracker,
            @Nullable String trackerMessage,
            boolean unfinished,
            boolean removed) {}

    /** The body of a 400: what is wrong with {@code since}. Of a 503: why the history can't be read. */
    @RegisterForReflection
    public record Unavailable(String reason) {}

    private final History history;
    private final ZoneId timeZone;

    HistoryApi(History history, SeedkeeperConfig config) {
        this.history = history;
        this.timeZone = config.timeZone();
    }

    @GET
    @Path("/runs")
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response runs(@QueryParam("since") @Nullable String since) {
        return answer(since, from -> history.runs(from).stream().map(this::run).toList());
    }

    @GET
    @Path("/removals")
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response removals(@QueryParam("since") @Nullable String since) {
        return answer(
                since,
                from -> history.removals(from).stream().map(this::removal).toList());
    }

    private Response answer(@Nullable String since, Function<Optional<Instant>, List<?>> read) {
        Optional<Instant> from;
        try {
            from = since(since);
        } catch (DateTimeParseException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            new Unavailable(
                                    "since=" + since
                                            + " is not a date (2026-11-20) or a date and time with an offset (2026-11-20T06:00:00+01:00)"))
                    .build();
        }
        try {
            return Response.ok(read.apply(from)).build();
        } catch (HistoryException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new Unavailable(e.getMessage()))
                    .build();
        }
    }

    /** A date is the start of that day in the configured time zone. Nothing, or an empty value, is no filter. */
    private Optional<Instant> since(@Nullable String since) {
        if (since == null || since.isBlank()) {
            return Optional.empty();
        }
        String value = since.strip();
        if (value.length() <= 10) {
            return Optional.of(LocalDate.parse(value).atStartOfDay(timeZone).toInstant());
        }
        // A + in a query string that was not encoded arrives as a space.
        return Optional.of(OffsetDateTime.parse(value.replace(' ', '+')).toInstant());
    }

    private Run run(RecordedRun run) {
        return new Run(
                run.id(),
                iso(run.started()),
                iso(run.ended()),
                run.status(),
                run.error().orElse(null),
                run.groupsRemoved(),
                run.torrentsRemoved(),
                run.filesDeleted(),
                run.spaceFreed());
    }

    private Removal removal(RecordedRemoval removal) {
        return new Removal(
                removal.runId(),
                iso(removal.started()),
                removal.reason(),
                removal.error().orElse(null),
                removal.spaceFreed(),
                removal.filesDeleted(),
                removal.pathsRefused(),
                removal.members().stream().map(HistoryApi::member).toList());
    }

    private static Member member(RecordedMember member) {
        return new Member(
                member.hash(),
                member.name(),
                member.tracker().orElse(null),
                member.trackerMessage().orElse(null),
                member.unfinished(),
                member.removed());
    }

    private String iso(Instant moment) {
        return moment.atZone(timeZone).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
