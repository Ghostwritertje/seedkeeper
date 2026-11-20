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
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
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
         * @param forecasts   true when one of the groups has a forecast: else the Forecast column would be empty
         * @param tally       how many groups have each verdict, among those with the reason of the filter; empty when
         *                    there are no verdicts
         * @param unknownFilter what is wrong with the filter, which is then ignored: the view shows every group; null
         *                    when the filter is known
         * @param unavailable why there are no verdicts right now; null when all is well
         * @param notice      why the keep override that was just set was not; null when there is nothing to tell
         * @param kept        true right after a keep override was set from this view
         */
        static native TemplateInstance groups(
                List<Group> groups,
                boolean forecasts,
                List<Tally> tally,
                Filter filter,
                @Nullable String unknownFilter,
                @Nullable String unavailable,
                @Nullable String notice,
                boolean kept);
    }

    /** The value of {@code done} that says a keep override was just set. */
    private static final String KEPT = "kept";

    private static final DateTimeFormatter FORECAST_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Where a long release name may break: after a dot or a dash that something follows. */
    private static final Pattern NAME_BREAKS = Pattern.compile("(?<=[.-])(?=.)");

    /**
     * One group, as text ready to show: a header row, then its members when it has more than one.
     *
     * @param name             the name of its first member, in the parts a line may break between
     * @param label            the name of its first member, whole, to name the group's controls
     * @param summary          "tracker-a · Seeding · 1 of 2 files linked" for a group of one, "3 torrents · 13 of 19
     *                         files linked" for more
     * @param progress         toward the minimum seed time: of its member for a group of one; for more, of its slowest
     *                         managed member, the one whose minimum is met last, as the forecast counts it
     * @param trackerMessage  the tracker message of its member, as {@link Member#trackerMessage()}, for a group of
     *                         one; else empty
     * @param verdictCode      the kebab-case code of the verdict, "left-alone"
     * @param reason           "Missing data (1 of 2 files)", or the label alone: the library links are in the summary
     * @param hint             what to do about the reason, for the reasons that need a person to look: "files are gone
     *                         from disk: re-verify or remove in Transmission"; empty for the others
     * @param secondaryReasons every other reason that holds, written like the reason and separated by commas
     * @param forecast         "2026-11-25 10:00" in the configured time zone, "clock stopped (paused)", "until 2027-03-01" (the
     *                         end date of a keep override), or empty
     * @param size             "3.2 GiB": the data of its wanted files, a file its members share counted once
     * @param members          its members, each on a row of its own; empty for a group of one, which its header row
     *                         shows whole
     * @param hashes           the info hashes of the members, for the form that sets a keep override
     */
    public record Group(
            List<String> name,
            String label,
            String summary,
            SeedProgress progress,
            String trackerMessage,
            String verdict,
            String verdictCode,
            String reason,
            String hint,
            String secondaryReasons,
            String forecast,
            String size,
            List<Member> members,
            List<String> hashes) {

        /** The rows of the group, which its cells after the seed time span: the header row and the member rows. */
        public int rows() {
            return 1 + members.size();
        }
    }

    /**
     * The filter: the chosen verdict, which the tally chooses, and every reason with how many groups of that verdict
     * it decided.
     *
     * @param verdictCode the code of the chosen verdict, to keep it in the filter form and the keep form; empty when
     *                    none is chosen
     * @param reasons     every reason, the chosen one selected
     * @param any         how many groups have the chosen verdict, whatever their reason
     * @param active      true when a verdict or a reason is chosen
     */
    public record Filter(String verdictCode, List<Option> reasons, long any, boolean active) {

        public boolean anyReason() {
            return reasons.stream().noneMatch(Option::selected);
        }

        /** The code of the chosen reason, to keep it in the links of the tally; empty when none is chosen. */
        public String reasonCode() {
            return reasons.stream()
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

    /**
     * One reason to filter on: the code that goes in the URL, the label to show and how many groups it decided.
     *
     * @param count how many groups of the chosen verdict it decided
     */
    public record Option(String code, String label, long count, boolean selected) {

        /** True when choosing it would show no group; the chosen one never is, so the form still says what it shows. */
        public boolean disabled() {
            return count == 0 && !selected;
        }
    }

    /**
     * One torrent of a group, as text ready to show.
     *
     * @param name     its name, in the parts a line may break between
     * @param tracker  the tracker's name; "Unmanaged (elsewhere.example)", with the announce hosts, or "Unmanaged (no
     *                 trackers)" when the torrent has no tracker
     * @param progress its seed time toward its minimum seed time
     * @param trackerMessage "Unregistered: Unregistered torrent", "Tracker error: Passkey does not exist!", or
     *                       empty when the tracker reports nothing wrong
     * @param size     "3.2 GiB": the data of its own wanted files
     * @param cause    {@code causes "Missing data"} when this torrent makes the group's reason hold; else empty
     */
    public record Member(
            List<String> name,
            String tracker,
            SeedProgress progress,
            String status,
            String trackerMessage,
            String size,
            String cause) {}

    /**
     * How far a seed time is toward its minimum seed time, as text ready to show. The minimum is met at the same moment
     * as the forecast counts it: once the seed time is at least the minimum.
     *
     * @param tone    the colour, as a {@code data-tone}: "warn" below the minimum, "ok" once it is met, "muted" with no
     *                minimum (unmanaged)
     * @param percent 0 to 99 below the minimum (rounded, but never 100 before it is met), 100 once it is met, 0 with no
     *                minimum
     * @param text    what the cell always shows: "33%", "Met ✓" or "—"
     * @param detail  the exact durations: "9d 20h of 30d · 20d 4h to go", "168d 10h of 30d · met 138d 10h ago", "400d
     *                · no minimum"
     */
    public record SeedProgress(String tone, int percent, String text, String detail) {

        private static final String BELOW = "warn";

        /** The progress of a group none of whose members has a minimum. */
        static final SeedProgress NO_MINIMUM = new SeedProgress("muted", 0, "—", "no member has a minimum");

        /** True below the minimum: the cell shows a meter. */
        public boolean below() {
            return BELOW.equals(tone);
        }

        static SeedProgress of(GroupVerdict.Member member) {
            return member.tracker()
                    .map(tracker -> toward(member.seedTime(), tracker.minimumSeedTime()))
                    .orElseGet(() -> new SeedProgress(
                            "muted", 0, "—", StatusText.duration(member.seedTime()) + " · no minimum"));
        }

        private static SeedProgress toward(Duration seedTime, Duration minimum) {
            String of = StatusText.duration(seedTime) + " of " + StatusText.duration(minimum) + " · ";
            Duration toGo = minimum.minus(seedTime);
            if (!toGo.isPositive()) {
                String since = toGo.isZero() ? "met just now" : "met " + StatusText.duration(toGo.negated()) + " ago";
                return new SeedProgress("ok", 100, "Met ✓", of + since);
            }
            int percent = (int) Math.min(99, Math.round(100.0 * seedTime.toSeconds() / minimum.toSeconds()));
            return new SeedProgress(BELOW, percent, percent + "%", of + StatusText.duration(toGo) + " to go");
        }

        /** The same progress, its detail saying it is that of the slowest member of a group. */
        SeedProgress slowest() {
            return new SeedProgress(tone, percent, text, "slowest: " + detail);
        }
    }

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
        String unknownFilter = null;
        try {
            filter = GroupsFilter.parse(verdict, reason);
        } catch (UnknownFilter e) {
            // Said above the whole view, so the tally and the form are there to choose a filter that exists.
            filter = GroupsFilter.NONE;
            unknownFilter = e.getMessage();
        }
        try {
            List<GroupVerdict> all = groups.groups();
            List<Group> shown =
                    all.stream().filter(filter::matches).map(GroupsPage::group).toList();
            return Templates.groups(
                    shown,
                    shown.stream().anyMatch(g -> !g.forecast().isEmpty()),
                    tally(all, filter),
                    filter(all, filter),
                    unknownFilter,
                    null,
                    notice,
                    kept);
        } catch (GroupsUnavailable e) {
            return Templates.groups(
                    List.of(),
                    false,
                    List.of(),
                    filter(List.of(), filter),
                    unknownFilter,
                    e.getMessage(),
                    notice,
                    kept);
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

    /** The filter with the count of every reason among the groups with the verdict of the filter. */
    private static Filter filter(List<GroupVerdict> all, GroupsFilter filter) {
        List<GroupVerdict> withVerdict = all.stream()
                .filter(new GroupsFilter(filter.verdict(), Optional.empty())::matches)
                .toList();
        return new Filter(
                filter.verdict().map(Verdict::code).orElse(""),
                Stream.of(Reason.values())
                        .map(r -> new Option(
                                r.code(),
                                r.label(),
                                withVerdict.stream()
                                        .filter(g -> g.reason() == r)
                                        .count(),
                                filter.reason().filter(r::equals).isPresent()))
                        .toList(),
                withVerdict.size(),
                filter.active());
    }

    static Group group(GroupVerdict group) {
        String libraryLinks = group.libraryLinks() + " of " + group.files() + " files linked";
        String forecast = group.forecast()
                .map(f -> f.until()
                        .map(endDate -> "until " + endDate)
                        .orElseGet(() -> f.date().map(FORECAST_DATE::format).orElse("clock stopped (paused)")))
                .orElse("");
        List<Member> members = group.members().stream()
                .map(member -> member(member, group.reason()))
                .toList();
        Member first = members.getFirst();
        boolean one = members.size() == 1;
        return new Group(
                first.name(),
                group.members().getFirst().name(),
                one
                        ? first.tracker() + " · " + first.status() + " · " + libraryLinks
                        : members.size() + " torrents · " + libraryLinks,
                one ? first.progress() : slowest(group.members()),
                one ? first.trackerMessage() : "",
                group.verdict().label(),
                group.verdict().code(),
                reason(group.reason(), group),
                hint(group.reason()),
                group.secondaryReasons().stream().map(r -> reason(r, group)).collect(Collectors.joining(", ")),
                forecast,
                StatusText.size(group.size()),
                one ? List.of() : members,
                group.members().stream().map(GroupVerdict.Member::hash).toList());
    }

    /** The label of a reason, with its count of the group's missing files when it has one. */
    private static String reason(Reason reason, GroupVerdict group) {
        return reason == Reason.MISSING_DATA
                ? reason.label() + " (" + group.missingFiles() + " of " + group.files() + " files)"
                : reason.label();
    }

    /** What to do about a reason that needs a person to look, in one line; empty for the others. */
    private static String hint(Reason reason) {
        return switch (reason) {
            case UNMANAGED -> "a member is on a tracker not in the config: add it under trackers to manage it";
            case MISSING_DATA -> "files are gone from disk: re-verify or remove in Transmission";
            case LOCAL_ERROR ->
                "Transmission can't read or write the data: fix the disk or its permissions, then re-verify";
            case VERIFYING -> "Transmission is checking the data: the verdict follows once it is done";
            default -> "";
        };
    }

    /** A release name in the parts a line may break between: after each dot and each dash. */
    static List<String> breakable(String name) {
        return List.of(NAME_BREAKS.split(name));
    }

    /**
     * The progress of the managed member that meets its minimum last: the most seed time to go, or the least past it.
     * Below the minimum, that member sets the forecast.
     */
    private static SeedProgress slowest(List<GroupVerdict.Member> members) {
        return members.stream()
                .filter(member -> member.tracker().isPresent())
                .max(Comparator.comparing((GroupVerdict.Member member) ->
                        member.tracker().orElseThrow().minimumSeedTime().minus(member.seedTime())))
                .map(member -> SeedProgress.of(member).slowest())
                .orElse(SeedProgress.NO_MINIMUM);
    }

    private static Member member(GroupVerdict.Member member, Reason reason) {
        return new Member(
                breakable(member.name()),
                member.tracker().map(Tracker::name).orElseGet(() -> unmanaged(member.announceHosts())),
                SeedProgress.of(member),
                StatusText.label(member.status()),
                member.trackerError().map(GroupsPage::trackerMessage).orElse(""),
                StatusText.size(member.size()),
                member.causes().contains(reason) ? "causes \"" + reason.label() + "\"" : "");
    }

    /**
     * "Unmanaged (elsewhere.example, tracker-a.example)", or "Unmanaged (no trackers)" when there are no hosts: written
     * like the reason.
     */
    private static String unmanaged(List<String> announceHosts) {
        return Reason.UNMANAGED.label() + " ("
                + (announceHosts.isEmpty() ? "no trackers" : String.join(", ", announceHosts)) + ")";
    }

    private static String trackerMessage(TrackerError error) {
        return error.message().isBlank() ? error.kind().label() : error.kind().label() + ": " + error.message();
    }
}
