package io.github.ghostwritertje.seedkeeper.system;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService.GroupsUnavailable;
import io.github.ghostwritertje.seedkeeper.system.SystemPage.LastRunText;
import io.github.ghostwritertje.seedkeeper.system.SystemStatus.LastRun;
import io.github.ghostwritertje.seedkeeper.system.SystemStatus.TransmissionStatus;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText.Moment;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText.Stamp;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriBuilder;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The overview at the bare URL, where a login lands: whether all is well and what happens next. Each number links to
 * the view that lists it.
 */
@Path("/")
class OverviewPage {

    @CheckedTemplate
    static class Templates {

        /**
         * @param warnings    what needs a look, most pressing first; empty when all is well
         * @param tally       how many verdict groups have each verdict, after the count of them all; empty when there
         *                    are no verdicts
         * @param unavailable why there are no verdicts right now; null when all is well
         * @param dueSoon     the kept verdict groups that may be removed soon; null when there are no verdicts
         * @param lastRun     the last run in words; null when there has been none
         * @param lastRunOk   true when the last run succeeded
         * @param nextRun     when the next scheduled run starts; null when no run is scheduled
         * @param timeZone    the configured time zone, which every time on the page is in: "Europe/Brussels"
         */
        static native TemplateInstance overview(
                List<Warning> warnings,
                List<Tally> tally,
                @Nullable String unavailable,
                @Nullable DueSoonGroups dueSoon,
                @Nullable LastRunText lastRun,
                boolean lastRunOk,
                @Nullable Moment nextRun,
                String timeZone);
    }

    /**
     * Something that needs a look.
     *
     * @param text what is wrong, in one line
     * @param href the view that tells more, or lists it
     */
    public record Warning(String text, String href) {}

    /**
     * How many verdict groups have one verdict, or any verdict when the code is empty.
     *
     * @param href the Groups view filtered by this verdict
     */
    public record Tally(String code, String label, long count, String href) {}

    /**
     * The kept verdict groups that may be removed soon.
     *
     * @param count   "1 kept group", "3 kept groups"
     * @param href    the Groups view of the kept groups, soonest forecast first
     * @param horizon until when a forecast is soon: the next run, or a week from now when that is later
     * @param groups  soonest first
     */
    public record DueSoonGroups(String count, String href, Stamp horizon, List<DueGroup> groups) {}

    /**
     * One verdict group that may be removed soon.
     *
     * @param name the name of its first member
     * @param href the Groups view searched for that name
     * @param when when it may be removed
     */
    public record DueGroup(String name, String href, Stamp when) {}

    /** The Groups view of the kept groups, soonest forecast first. */
    static final String KEPT_BY_FORECAST = "/groups?verdict=keep&sort=forecast";

    private final SystemService system;
    private final GroupsService groups;
    private final ZoneId timeZone;

    OverviewPage(SystemService system, GroupsService groups, SeedkeeperConfig config) {
        this.system = system;
        this.groups = groups;
        this.timeZone = config.timeZone();
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public TemplateInstance overview() {
        SystemStatus status = system.status();
        Instant now = Instant.now();
        LastRun lastRun = status.lastRun();
        String nextRun = status.nextRun();
        List<Warning> warnings = new ArrayList<>(runWarnings(status.transmission(), lastRun));
        List<Tally> tally = List.of();
        String unavailable = null;
        DueSoonGroups dueSoon = null;
        try {
            List<GroupVerdict> all = groups.groups();
            tally = tally(all);
            dueSoon = dueSoon(all, DueSoon.horizon(Optional.ofNullable(nextRun).map(SystemPage::instant), now));
            leftAlone(all).ifPresent(warnings::add);
        } catch (GroupsUnavailable e) {
            unavailable = e.getMessage();
        }
        return Templates.overview(
                warnings,
                tally,
                unavailable,
                dueSoon,
                lastRun == null ? null : SystemPage.text(lastRun, timeZone, now),
                lastRun != null && lastRun.succeeded(),
                nextRun == null ? null : SystemPage.moment(nextRun, timeZone, now),
                timeZone.getId());
    }

    /** Transmission seedkeeper can't work with, and a last run that failed. */
    private static List<Warning> runWarnings(TransmissionStatus transmission, @Nullable LastRun lastRun) {
        List<Warning> warnings = new ArrayList<>();
        String reason = transmission.reason();
        if (reason != null) {
            warnings.add(new Warning("Transmission: " + reason, "/system"));
        }
        String error = lastRun == null ? null : lastRun.error();
        if (error != null) {
            warnings.add(new Warning("The last run failed: " + error, "/activity"));
        }
        return warnings;
    }

    private static Optional<Warning> leftAlone(List<GroupVerdict> all) {
        long count = StatusFragment.leftAlone(all);
        if (count == 0) {
            return Optional.empty();
        }
        String text = count == 1
                ? "1 group is left alone: someone should look at it"
                : count + " groups are left alone: someone should look at them";
        return Optional.of(new Warning(text, "/groups?verdict=" + Verdict.LEFT_ALONE.code()));
    }

    private static List<Tally> tally(List<GroupVerdict> all) {
        return Stream.concat(
                        Stream.of(new Tally("", "All", all.size(), "/groups")),
                        Stream.of(Verdict.values())
                                .map(v -> new Tally(
                                        v.code(),
                                        v.label(),
                                        all.stream()
                                                .filter(g -> g.verdict() == v)
                                                .count(),
                                        "/groups?verdict=" + v.code())))
                .toList();
    }

    private DueSoonGroups dueSoon(List<GroupVerdict> all, Instant horizon) {
        List<DueGroup> due = DueSoon.of(all, horizon, timeZone).stream()
                .map(d -> {
                    String name = d.group().members().getFirst().name();
                    return new DueGroup(
                            name,
                            UriBuilder.fromPath("/groups")
                                    .queryParam("q", name)
                                    .build()
                                    .toString(),
                            StatusText.stamp(d.when()));
                })
                .toList();
        return new DueSoonGroups(
                SystemPage.count(due.size(), "kept group"),
                KEPT_BY_FORECAST,
                StatusText.stamp(horizon.atZone(timeZone)),
                due);
    }
}
