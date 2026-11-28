package io.github.ghostwritertje.seedkeeper.system;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.run.RunService;
import io.github.ghostwritertje.seedkeeper.system.SystemStatus.LastRun;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText.Moment;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;

/** The System view, with its Run now button. */
@Path("/system")
class SystemPage {

    @CheckedTemplate
    static class Templates {

        /**
         * @param lastRun  the last run in words; null when there has been none
         * @param nextRun  when the next scheduled run starts; null when no run is scheduled
         * @param timeZone the configured time zone, which every time on the page is in: "Europe/Brussels"
         * @param notice   what to tell about the Run now that was just pressed; null when there is nothing to tell
         */
        static native TemplateInstance system(
                SystemStatus status,
                @Nullable LastRunText lastRun,
                @Nullable Moment nextRun,
                String timeZone,
                @Nullable String notice);
    }

    /**
     * The last run as the page words it.
     *
     * @param started    when it started, in the configured time zone
     * @param took       how long it took: "4s"
     * @param removed    "2 torrents in 1 group"
     * @param spaceFreed "1.5 GiB"
     */
    public record LastRunText(Moment started, String took, String removed, String spaceFreed) {}

    private final SystemService system;
    private final RunService runs;
    private final ZoneId timeZone;

    SystemPage(SystemService system, RunService runs, SeedkeeperConfig config) {
        this.system = system;
        this.runs = runs;
        this.timeZone = config.timeZone();
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public TemplateInstance system() {
        return page(null);
    }

    /**
     * The Run now button: does a run and answers when it has ended, with the System view and so the outcome. A form
     * post, so it needs the CSRF token of the page, and a login: it is not in the API.
     */
    @POST
    @Path("/run")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public Response runNow() {
        if (runs.run().isEmpty()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(page("A run is already running."))
                    .build();
        }
        return Response.seeOther(URI.create("/system")).build();
    }

    private TemplateInstance page(@Nullable String notice) {
        SystemStatus status = system.status();
        LastRun lastRun = status.lastRun();
        String nextRun = status.nextRun();
        Instant now = Instant.now();
        return Templates.system(
                status,
                lastRun == null ? null : text(lastRun, timeZone, now),
                nextRun == null ? null : moment(nextRun, timeZone, now),
                timeZone.getId(),
                notice);
    }

    /** The last run as the System view and the overview word it. */
    static LastRunText text(LastRun run, ZoneId timeZone, Instant now) {
        return new LastRunText(
                moment(run.started(), timeZone, now),
                StatusText.elapsed(Duration.between(instant(run.started()), instant(run.ended()))),
                count(run.torrentsRemoved(), "torrent") + " in " + count(run.groupsRemoved(), "group"),
                StatusText.size(run.spaceFreedBytes()));
    }

    /** A moment of {@link SystemStatus}, ISO 8601, as the pages show it. */
    static Moment moment(String iso, ZoneId timeZone, Instant now) {
        return StatusText.moment(instant(iso), timeZone, now);
    }

    static Instant instant(String iso) {
        return OffsetDateTime.parse(iso).toInstant();
    }

    /** "1 group", "2 groups". */
    static String count(int number, String what) {
        return number + " " + what + (number == 1 ? "" : "s");
    }
}
