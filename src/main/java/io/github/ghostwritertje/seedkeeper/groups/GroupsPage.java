package io.github.ghostwritertje.seedkeeper.groups;

import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.decide.Tracker;
import io.github.ghostwritertje.seedkeeper.decide.TrackerError;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import io.github.ghostwritertje.seedkeeper.groups.GroupsFilter.UnknownFilter;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService.GroupsUnavailable;
import io.github.ghostwritertje.seedkeeper.keep.OverrideChanges;
import io.github.ghostwritertje.seedkeeper.keep.OverridesException;
import io.github.ghostwritertje.seedkeeper.keep.OverridesException.Problem;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.resteasy.reactive.RestForm;
import org.jspecify.annotations.Nullable;

/** The Groups view, with its form to set a keep override on a group. */
@Path("/groups")
class GroupsPage {

    @CheckedTemplate
    static class Templates {

        /**
         * @param groups      the groups that match the filter
         * @param tally       how many groups have each verdict, among those with the reason of the filter; empty when
         *                    there are no verdicts
         * @param unavailable why there are no verdicts right now, or what is wrong with the filter; null when all
         *                    is well
         * @param notice      why the keep override that was just set was not; null when there is nothing to tell
         * @param kept        true right after a keep override was set from this view
         */
        static native TemplateInstance groups(
                List<Group> groups,
                List<Tally> tally,
                Filter filter,
                @Nullable String unavailable,
                @Nullable String notice,
                boolean kept);
    }

    /** The value of {@code done} that says a keep override was just set. */
    private static final String KEPT = "kept";

    private static final DateTimeFormatter FORECAST_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * One group, as text ready to show.
     *
     * @param verdictCode      the kebab-case code of the verdict, "left-alone"
     * @param reason           "Library link (1 of 2 files)", "Missing data (1 of 2 files)", or the label alone
     * @param secondaryReasons every other reason that holds, written like the reason and separated by commas
     * @param forecast         "2026-11-25 10:00" in the configured time zone, "clock stopped (paused)", "until 2027-03-01" (the
     *                         end date of a keep override), or empty
     * @param hashes           the info hashes of the members, for the form that sets a keep override
     */
    public record Group(
            String verdict,
            String verdictCode,
            String reason,
            String secondaryReasons,
            String libraryLinks,
            String forecast,
            List<Member> members,
            List<String> hashes) {}

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

        /** The code of the chosen verdict, to keep it in the keep form; empty when none is chosen. */
        public String verdictCode() {
            return selectedCode(verdicts);
        }

        /** The code of the chosen reason, to keep it in the links of the tally; empty when none is chosen. */
        public String reasonCode() {
            return selectedCode(reasons);
        }

        private static String selectedCode(List<Option> options) {
            return options.stream()
                    .filter(Option::selected)
                    .map(Option::code)
                    .findFirst()
                    .orElse("");
        }
    }

    /**
     * How many groups have one verdict, or any verdict when the code is empty.
     *
     * @param selected true when this is the verdict the filter shows
     */
    public record Tally(String code, String label, long count, boolean selected) {}

    /** One choice of a filter: the code that goes in the URL and the label to show. */
    public record Option(String code, String label, boolean selected) {}

    /**
     * One torrent of a group, as text ready to show.
     *
     * @param tracker  "unmanaged" when the torrent has no tracker
     * @param seedTime "40d / 30d": the seed time and the tracker's minimum; the seed time alone when unmanaged
     * @param trackerMessage "Unregistered: Unregistered torrent", "Tracker error: Passkey does not exist!", or
     *                       empty when the tracker reports nothing wrong
     */
    public record Member(String name, String tracker, String seedTime, String status, String trackerMessage) {}

    private final GroupsService groups;
    private final OverrideChanges overrides;

    GroupsPage(GroupsService groups, OverrideChanges overrides) {
        this.groups = groups;
        this.overrides = overrides;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public TemplateInstance groups(
            @QueryParam("verdict") @Nullable String verdict,
            @QueryParam("reason") @Nullable String reason,
            @QueryParam("done") @Nullable String done) {
        // Only the one known value turns the status line on: the parameter itself is never shown.
        return page(verdict, reason, null, KEPT.equals(done));
    }

    /**
     * Set a keep override: adds one with the info hashes of every member of a group, then sends the browser back to
     * the Groups view with the filter the form was posted from. A form post, so it needs the CSRF token of the page,
     * and a login: it is not in the API.
     *
     * @param hash    the info hash of each member, one field per member
     * @param until   "2027-03-01", or empty for no end date
     * @param verdict the code of the verdict of the filter the form was posted from, or empty for any verdict
     * @param reason  the code of the reason of the filter the form was posted from, or empty for any reason
     */
    @POST
    @Path("/keep")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public Response keep(
            @RestForm List<String> hash,
            @RestForm String note,
            @RestForm String until,
            @RestForm @Nullable String verdict,
            @RestForm @Nullable String reason) {
        try {
            overrides.add(hash == null ? List.of() : hash, note, OverrideChanges.date(until));
            return Response.seeOther(afterKeep(verdict, reason)).build();
        } catch (OverridesException e) {
            // Adding never looks for an existing override, so NO_SUCH_OVERRIDE can't happen here.
            Response.Status status =
                    e.problem() == Problem.INVALID ? Response.Status.BAD_REQUEST : Response.Status.SERVICE_UNAVAILABLE;
            return Response.status(status)
                    .entity(page(verdict, reason, e.getMessage(), false))
                    .build();
        }
    }

    /**
     * Where the browser goes once a keep override is set: the Groups view with the same filter, saying it was set.
     * The filter is not checked here: the Groups view says so when it is unknown.
     */
    static URI afterKeep(@Nullable String verdict, @Nullable String reason) {
        UriBuilder uri = UriBuilder.fromPath("/groups");
        if (verdict != null && !verdict.isBlank()) {
            uri.queryParam("verdict", verdict);
        }
        if (reason != null && !reason.isBlank()) {
            uri.queryParam("reason", reason);
        }
        return uri.queryParam("done", KEPT).build();
    }

    private TemplateInstance page(
            @Nullable String verdict, @Nullable String reason, @Nullable String notice, boolean kept) {
        GroupsFilter filter;
        try {
            filter = GroupsFilter.parse(verdict, reason);
        } catch (UnknownFilter e) {
            return Templates.groups(List.of(), List.of(), filter(GroupsFilter.NONE), e.getMessage(), notice, kept);
        }
        try {
            List<GroupVerdict> all = groups.groups();
            return Templates.groups(
                    all.stream().filter(filter::matches).map(GroupsPage::group).toList(),
                    tally(all, filter),
                    filter(filter),
                    null,
                    notice,
                    kept);
        } catch (GroupsUnavailable e) {
            return Templates.groups(List.of(), List.of(), filter(filter), e.getMessage(), notice, kept);
        }
    }

    /** The count of every verdict among the groups with the reason of the filter, after the count of them all. */
    private static List<Tally> tally(List<GroupVerdict> all, GroupsFilter filter) {
        List<GroupVerdict> withReason = all.stream()
                .filter(new GroupsFilter(Optional.empty(), filter.reason())::matches)
                .toList();
        return Stream.concat(
                        Stream.of(new Tally(
                                "", "All", withReason.size(), filter.verdict().isEmpty())),
                        Stream.of(Verdict.values())
                                .map(v -> new Tally(
                                        v.code(),
                                        v.label(),
                                        withReason.stream()
                                                .filter(g -> g.verdict() == v)
                                                .count(),
                                        filter.verdict().filter(v::equals).isPresent())))
                .toList();
    }

    private static Filter filter(GroupsFilter filter) {
        return new Filter(
                Stream.of(Verdict.values())
                        .map(v -> new Option(
                                v.code(),
                                v.label(),
                                filter.verdict().filter(v::equals).isPresent()))
                        .toList(),
                Stream.of(Reason.values())
                        .map(r -> new Option(
                                r.code(),
                                r.label(),
                                filter.reason().filter(r::equals).isPresent()))
                        .toList(),
                filter.active());
    }

    private static Group group(GroupVerdict group) {
        String libraryLinks = group.libraryLinks() + " of " + group.files() + " files";
        String forecast = group.forecast()
                .map(f -> f.until()
                        .map(endDate -> "until " + endDate)
                        .orElseGet(() -> f.date().map(FORECAST_DATE::format).orElse("clock stopped (paused)")))
                .orElse("");
        return new Group(
                group.verdict().label(),
                group.verdict().code(),
                reason(group.reason(), group),
                group.secondaryReasons().stream().map(r -> reason(r, group)).collect(Collectors.joining(", ")),
                libraryLinks,
                forecast,
                group.members().stream().map(GroupsPage::member).toList(),
                group.members().stream().map(GroupVerdict.Member::hash).toList());
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
        return new Member(
                member.name(),
                member.tracker().map(Tracker::name).orElse("unmanaged"),
                member.tracker()
                        .map(tracker -> seedTime + " / " + StatusText.duration(tracker.minimumSeedTime()))
                        .orElse(seedTime),
                StatusText.label(member.status()),
                member.trackerError().map(GroupsPage::trackerMessage).orElse(""));
    }

    private static String trackerMessage(TrackerError error) {
        return error.message().isBlank() ? error.kind().label() : error.kind().label() + ": " + error.message();
    }
}
