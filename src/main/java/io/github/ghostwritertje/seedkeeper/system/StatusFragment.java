package io.github.ghostwritertje.seedkeeper.system;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService.GroupsUnavailable;
import io.github.ghostwritertje.seedkeeper.system.SystemStatus.LastRun;
import io.github.ghostwritertje.seedkeeper.system.SystemStatus.TransmissionStatus;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText.Moment;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The status block at the foot of the sidebar, and the count on its Groups link: an HTML fragment that every page
 * loads after itself, so that no page waits on Transmission for it.
 */
@Path("/status")
class StatusFragment {

    @CheckedTemplate
    static class Templates {

        /**
         * @param transmission whether seedkeeper can work with Transmission
         * @param nextRun      when the next scheduled run starts; null when no run is scheduled
         * @param lastRun      when the last run started; null when there has been none
         * @param leftAlone    how many verdict groups are left alone; 0 also when there are no verdicts right now
         */
        static native TemplateInstance status(
                TransmissionStatus transmission, @Nullable Moment nextRun, @Nullable Moment lastRun, long leftAlone);
    }

    private final SystemService system;
    private final GroupsService groups;
    private final ZoneId timeZone;

    StatusFragment(SystemService system, GroupsService groups, SeedkeeperConfig config) {
        this.system = system;
        this.groups = groups;
        this.timeZone = config.timeZone();
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public TemplateInstance status() {
        SystemStatus status = system.status();
        Instant now = Instant.now();
        LastRun lastRun = status.lastRun();
        String nextRun = status.nextRun();
        return Templates.status(
                status.transmission(),
                nextRun == null ? null : SystemPage.moment(nextRun, timeZone, now),
                lastRun == null ? null : SystemPage.moment(lastRun.started(), timeZone, now),
                // Without a usable Transmission there are no verdicts: no need to ask it again.
                status.transmission().usable() ? leftAlone() : 0);
    }

    private long leftAlone() {
        try {
            return leftAlone(groups.groups());
        } catch (GroupsUnavailable e) {
            return 0;
        }
    }

    static long leftAlone(List<GroupVerdict> all) {
        return all.stream().filter(g -> g.verdict() == Verdict.LEFT_ALONE).count();
    }
}
