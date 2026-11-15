package io.github.ghostwritertje.seedkeeper.groups;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.decide.Tracker;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import io.github.ghostwritertje.seedkeeper.groups.GroupsFilter.UnknownFilter;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService.GroupsUnavailable;
import jakarta.ws.rs.QueryParam;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** The Groups view. */
@Path("/groups")
public class GroupsPage {

    @CheckedTemplate
    static class Templates {

        /**
         * @param groups      the groups that match the filter
         * @param unavailable why there are no verdicts right now, or what is wrong with the filter; null when all
         *                    is well
         */
        static native TemplateInstance groups(List<Group> groups, Filter filter, String unavailable);
    }

    private static final DateTimeFormatter FORECAST_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * One group, as text ready to show.
     *
     * @param reason           "Library link (1 of 2 files)", "Missing data (1 of 2 files)", or the label alone
     * @param secondaryReasons every other reason that holds, written like the reason and separated by commas
     * @param forecast         "2026-11-25 10:00" in the configured time zone, "clock stopped (paused)", or empty
     */
    public record Group(String verdict, String reason, String secondaryReasons, String libraryLinks, String forecast,
            List<Member> members) {
    }

    /**
     * The filter form: every verdict and every reason, with the chosen ones selected.
     *
     * @param active true when a verdict or a reason is chosen
     */
    public record Filter(List<Option> verdicts, List<Option> reasons, boolean active) {

        public boolean anyVerdict() {
            return verdicts.stream().noneMatch(Option::selected);
        }

        public boolean anyReason() {
            return reasons.stream().noneMatch(Option::selected);
        }
    }

    /** One choice of a filter: the code that goes in the URL and the label to show. */
    public record Option(String code, String label, boolean selected) {
    }

    /**
     * @param tracker  "unmanaged" when the torrent has no tracker
     * @param seedTime "40d / 30d": the seed time and the tracker's minimum; the seed time alone when unmanaged
     */
    public record Member(String name, String tracker, String seedTime, String status) {
    }

    private final GroupsService groups;

    GroupsPage(GroupsService groups) {
        this.groups = groups;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public TemplateInstance groups(@QueryParam("verdict") String verdict, @QueryParam("reason") String reason) {
        GroupsFilter filter;
        try {
            filter = GroupsFilter.parse(verdict, reason);
        } catch (UnknownFilter e) {
            return Templates.groups(List.of(), filter(GroupsFilter.NONE), e.getMessage());
        }
        try {
            return Templates.groups(groups.groups().stream().filter(filter::matches).map(GroupsPage::group).toList(),
                    filter(filter), null);
        } catch (GroupsUnavailable e) {
            return Templates.groups(List.of(), filter(filter), e.getMessage());
        }
    }

    private static Filter filter(GroupsFilter filter) {
        return new Filter(
                Stream.of(Verdict.values())
                        .map(v -> new Option(v.code(), v.label(), filter.verdict().filter(v::equals).isPresent()))
                        .toList(),
                Stream.of(Reason.values())
                        .map(r -> new Option(r.code(), r.label(), filter.reason().filter(r::equals).isPresent()))
                        .toList(),
                filter.active());
    }

    private static Group group(GroupVerdict group) {
        String libraryLinks = group.libraryLinks() + " of " + group.files() + " files";
        String forecast = group.forecast()
                .map(f -> f.date().map(FORECAST_DATE::format).orElse("clock stopped (paused)"))
                .orElse("");
        return new Group(group.verdict().label(), reason(group.reason(), group),
                group.secondaryReasons().stream().map(r -> reason(r, group)).collect(Collectors.joining(", ")),
                libraryLinks, forecast, group.members().stream().map(GroupsPage::member).toList());
    }

    /** The label of a reason, with its count of the group's files when it has one. */
    private static String reason(Reason reason, GroupVerdict group) {
        return switch (reason) {
            case LIBRARY_LINK -> reason.label() + " (" + group.libraryLinks() + " of " + group.files() + " files)";
            case MISSING_DATA -> reason.label() + " (" + group.missingFiles() + " of " + group.files() + " files)";
            default -> reason.label();
        };
    }

    private static Member member(GroupVerdict.Member member) {
        String seedTime = StatusText.duration(member.seedTime());
        return new Member(member.name(), member.tracker().map(Tracker::name).orElse("unmanaged"),
                member.tracker().map(tracker -> seedTime + " / " + StatusText.duration(tracker.minimumSeedTime()))
                        .orElse(seedTime),
                StatusText.label(member.status()));
    }
}
