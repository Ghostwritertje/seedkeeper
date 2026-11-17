package io.github.ghostwritertje.seedkeeper.system;

import io.github.ghostwritertje.seedkeeper.run.RunService;
import io.github.ghostwritertje.seedkeeper.system.SystemStatus.LastRun;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;

/** The System view, with its Run now button. */
@Path("/system")
class SystemPage {

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @CheckedTemplate
    static class Templates {

        /**
         * @param lastRun the last run in words; null when there has been none
         * @param nextRun "2026-11-21 06:00:00", in the configured time zone; null when no run is scheduled
         * @param notice  what to tell about the Run now that was just pressed; null when there is nothing to tell
         */
        static native TemplateInstance system(
                SystemStatus status, @Nullable LastRunText lastRun, @Nullable String nextRun, @Nullable String notice);
    }

    /**
     * The last run as the page words it.
     *
     * @param started    "2026-11-20 06:00:00", in the configured time zone
     * @param removed    "2 torrents in 1 group"
     * @param spaceFreed "1.5 GiB"
     */
    public record LastRunText(String started, String ended, String removed, String spaceFreed) {}

    private final SystemService system;
    private final RunService runs;

    SystemPage(SystemService system, RunService runs) {
        this.system = system;
        this.runs = runs;
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
        return Templates.system(
                status, lastRun == null ? null : text(lastRun), nextRun == null ? null : moment(nextRun), notice);
    }

    private static LastRunText text(LastRun run) {
        return new LastRunText(
                moment(run.started()),
                moment(run.ended()),
                count(run.torrentsRemoved(), "torrent") + " in " + count(run.groupsRemoved(), "group"),
                StatusText.size(run.spaceFreedBytes()));
    }

    private static String moment(String iso) {
        return OffsetDateTime.parse(iso).format(MOMENT);
    }

    private static String count(int number, String what) {
        return number + " " + what + (number == 1 ? "" : "s");
    }
}
