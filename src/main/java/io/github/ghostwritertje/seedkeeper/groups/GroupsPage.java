package io.github.ghostwritertje.seedkeeper.groups;

import io.github.ghostwritertje.seedkeeper.decide.Forecast;
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
import io.github.ghostwritertje.seedkeeper.transmission.StatusText.Stamp;
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
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
         * @param lanes         one per verdict, Keep, Left alone, Remove from left to right; only the lane of the
         *                      verdict of the filter when it names one; empty when there are no verdicts
         * @param found         how many groups match the filter, in every lane
         * @param pages         which page of the lane of the verdict of the filter this is, out of how many; one page
         *                      when the filter names no verdict, as each lane then shows its first groups
         * @param unknownFilter what is wrong with the filter, which is then ignored: the view shows every group; null
         *                      when the filter is known
         * @param unavailable   why there are no verdicts right now; null when all is well
         * @param notice      why the keep override that was just set was not; null when there is nothing to tell
         * @param kept        true right after a keep override was set from this view
         */
        static native TemplateInstance groups(
                List<Lane> lanes,
                int found,
                Filter filter,
                Pages pages,
                @Nullable String unknownFilter,
                @Nullable String unavailable,
                @Nullable String notice,
                boolean kept);
    }

    /** How many groups a lane shows at most: of each lane, or of one page of the lane of the verdict chosen. */
    static final int PAGE_SIZE = 50;

    /** The verdicts of the lanes, from left to right. */
    private static final List<Verdict> LANES = List.of(Verdict.KEEP, Verdict.LEFT_ALONE, Verdict.REMOVE);

    /** The columns the view can be sorted by, with their labels: the verdict is the lane, so it is not one of them. */
    private static final List<Map.Entry<GroupsOrder.Column, String>> SORTS = List.of(
            Map.entry(GroupsOrder.Column.NAME, "Name"),
            Map.entry(GroupsOrder.Column.SEEDING, "Seed time toward the minimum"),
            Map.entry(GroupsOrder.Column.FORECAST, "Forecast"),
            Map.entry(GroupsOrder.Column.SIZE, "Size"));

    /**
     * What a request to the Groups view asks for, to write it back into links and forms: the filter, the order and the
     * page. The codes are as the request gives them, stripped: the view checks them.
     *
     * @param verdict the code of a verdict; empty for any
     * @param reason  the code of a reason; empty for any
     * @param name    the text a member's name contains; empty for any
     * @param page    1-based
     */
    record Query(String verdict, String reason, String name, GroupsOrder order, int page) {

        static Query of(
                @Nullable String verdict,
                @Nullable String reason,
                @Nullable String name,
                @Nullable String sort,
                @Nullable String dir,
                @Nullable String page) {
            return new Query(
                    strip(verdict), strip(reason), strip(name), GroupsOrder.parse(sort, dir), Pages.number(page));
        }

        /** The URL of the Groups view with this query: what is empty, the default order and page 1 are left out. */
        String href() {
            return uri().build().toString();
        }

        UriBuilder uri() {
            UriBuilder uri = UriBuilder.fromPath("/groups");
            param(uri, "verdict", verdict);
            param(uri, "reason", reason);
            param(uri, "q", name);
            if (!order.equals(GroupsOrder.DEFAULT)) {
                uri.queryParam("sort", order.column().code()).queryParam("dir", order.dir());
            }
            if (page > 1) {
                uri.queryParam("page", page);
            }
            return uri;
        }

        Query verdict(String code) {
            return new Query(code, reason, name, order, 1);
        }

        Query page(int number) {
            return new Query(verdict, reason, name, order, number);
        }

        /** The same order with no filter. */
        Query cleared() {
            return new Query("", "", "", order, 1);
        }

        private static void param(UriBuilder uri, String param, String value) {
            if (!value.isEmpty()) {
                uri.queryParam(param, value);
            }
        }

        private static String strip(@Nullable String value) {
            return value == null ? "" : value.strip();
        }
    }

    /**
     * The lane of one verdict: its count, what it holds, and a card for each of its groups.
     *
     * @param code        the kebab-case code of the verdict, "left-alone"
     * @param label       the verdict, "Left alone": the lane's heading says it, not only its colour
     * @param explanation what the lane holds, in one line: "a person should look"
     * @param count       how many groups of this verdict match the filter, on every page
     * @param groups      the groups it shows, in the order chosen: at most {@link #PAGE_SIZE}
     * @param showAll     the URL of this lane alone, which pages, when it holds more groups than it shows here; else
     *                    empty
     */
    public record Lane(
            String code, String label, String explanation, long count, List<Group> groups, String showAll) {}

    /**
     * One way to sort the view, in the Sort field: the order applies within every lane.
     *
     * @param code the code {@code sort} names it by; empty for the order of the lanes
     */
    public record SortOption(String code, String label, boolean selected) {}

    /**
     * The page of groups the view shows, and how many pages there are.
     *
     * @param page     1-based, between 1 and {@code count}
     * @param count    1 when there are no groups
     * @param groups   how many groups match the filter, on every page
     * @param previous the URL of the page before; empty on the first
     * @param next     the URL of the page after; empty on the last
     */
    public record Pages(int page, int count, int groups, String previous, String next) {

        /** The page a request asks for: 1 when it is below 1 or no number, at most as many as {@code int} holds. */
        static int number(@Nullable String requested) {
            if (requested == null || !requested.strip().matches("[+-]?\\d+")) {
                return 1;
            }
            return new BigInteger(requested.strip())
                    .max(BigInteger.ONE)
                    .min(BigInteger.valueOf(Integer.MAX_VALUE))
                    .intValueExact();
        }

        /** The page of the query, the last one when it asks for one beyond it. */
        static Pages of(Query query, int groups) {
            int count = Math.max(1, (groups + PAGE_SIZE - 1) / PAGE_SIZE);
            int page = Math.min(query.page(), count);
            return new Pages(
                    page,
                    count,
                    groups,
                    page > 1 ? query.page(page - 1).href() : "",
                    page < count ? query.page(page + 1).href() : "");
        }

        /** The groups of this page, out of all that match. */
        <T> List<T> of(List<T> all) {
            int from = (page - 1) * PAGE_SIZE;
            return all.subList(Math.min(from, all.size()), Math.min(from + PAGE_SIZE, all.size()));
        }

        /** Whether there is more than one page, so the view links between them. */
        public boolean several() {
            return count > 1;
        }
    }

    /** The value of {@code done} that says a keep override was just set. */
    private static final String KEPT = "kept";

    /** Where a long release name may break: after a dot or a dash that something follows. */
    private static final Pattern NAME_BREAKS = Pattern.compile("(?<=[.-])(?=.)");

    /**
     * One group, as text ready to show: a card in the lane of its verdict, its members on it when it has more than
     * one.
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
     * @param forecastDate     the forecast when it is a moment, for a {@code <time>}; null when it is anything else
     * @param size             "3.2 GiB": the data of its wanted files, a file its members share counted once
     * @param members          its members, which open on demand; empty for a group of one, which its card shows whole
     * @param hashes           the info hashes of the members, for the form that sets a keep override
     * @param keepable         false when a keep override already keeps it: its card has no form to set one
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
            @Nullable Stamp forecastDate,
            String size,
            List<Member> members,
            List<String> hashes,
            boolean keepable) {}

    /**
     * The filter and the order: the chosen verdict, which a lane's "Show all" link chooses, every reason with how many
     * groups of that verdict it decided, the name searched for, and the ways to sort the lanes.
     *
     * @param verdictCode the code of the chosen verdict, to keep it in the filter form and the keep form; empty when
     *                    none is chosen
     * @param reasons     every reason, the chosen one selected
     * @param any         how many groups have the chosen verdict and the name, whatever their reason
     * @param name        the text a member's name contains, as it was searched for; empty for any
     * @param active      true when a verdict, a reason or a name is chosen
     * @param sorts       the ways to sort, the order of the lanes first; the chosen one selected
     * @param sort        the code of the column the view is sorted by, for the forms to keep it; empty for the
     *                    default order
     * @param dir         "asc" or "desc", the way the view is sorted; empty for the default order
     * @param page        the page shown, for the keep form to come back to; empty for the first
     * @param clear       the URL of the view with no filter, in the same order
     * @param allLanes    the URL of every lane, the rest of the filter and the order kept, when a verdict is chosen;
     *                    else empty
     */
    public record Filter(
            String verdictCode,
            List<Option> reasons,
            long any,
            String name,
            boolean active,
            List<SortOption> sorts,
            String sort,
            String dir,
            String page,
            String clear,
            String allLanes) {

        /** True when the order chosen is turned round. */
        public boolean descending() {
            return GroupsOrder.DESCENDING.equals(dir);
        }

        public boolean anyReason() {
            return reasons.stream().noneMatch(Option::selected);
        }

        /** The code of the chosen reason, to keep it in the keep form; empty when none is chosen. */
        public String reasonCode() {
            return reasons.stream()
                    .filter(Option::selected)
                    .map(Option::code)
                    .findFirst()
                    .orElse("");
        }
    }

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
            @QueryParam("q") @Nullable String q,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @Nullable String dir,
            @QueryParam("page") @Nullable String page,
            @QueryParam("done") @Nullable String done) {
        // Only the one known value turns the status line on: the parameter itself is never shown.
        return page(Query.of(verdict, reason, q, sort, dir, page), null, KEPT.equals(done));
    }

    /**
     * Set a keep override: adds one with the info hashes of every member of a group, then sends the browser back to
     * the Groups view with the filter, the order and the page the form was posted from. A form post, so it needs the
     * CSRF token of the page, and a login: it is not in the API.
     *
     * @param hash    the info hash of each member, one field per member
     * @param until   "2027-03-01", or empty for no end date
     * @param verdict the code of the verdict of the filter the form was posted from, or empty for any verdict
     * @param reason  the code of the reason of the filter the form was posted from, or empty for any reason
     * @param q       the name searched for in the view the form was posted from, or empty for any name
     * @param sort    the code of the column the view was sorted by, or empty for the default order
     * @param dir     the way the view was sorted, or empty for the default order
     * @param page    the page of the view, or empty for the first
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
            @RestForm @Nullable String reason,
            @RestForm @Nullable String q,
            @RestForm @Nullable String sort,
            @RestForm @Nullable String dir,
            @RestForm @Nullable String page) {
        Query query = Query.of(verdict, reason, q, sort, dir, page);
        try {
            overrides.add(hash == null ? List.of() : hash, note, OverrideChanges.date(until));
            return Response.seeOther(afterKeep(query)).build();
        } catch (OverridesException e) {
            // Adding never looks for an existing override, so NO_SUCH_OVERRIDE can't happen here.
            Response.Status status =
                    e.problem() == Problem.INVALID ? Response.Status.BAD_REQUEST : Response.Status.SERVICE_UNAVAILABLE;
            return Response.status(status)
                    .entity(page(query, e.getMessage(), false))
                    .build();
        }
    }

    /**
     * Where the browser goes once a keep override is set: the Groups view with the same filter, order and page,
     * saying it was set. The filter is not checked here: the Groups view says so when it is unknown.
     */
    static URI afterKeep(Query query) {
        return query.uri().queryParam("done", KEPT).build();
    }

    private TemplateInstance page(Query query, @Nullable String notice, boolean kept) {
        GroupsFilter filter;
        String unknownFilter = null;
        Query shown = query;
        try {
            filter = GroupsFilter.parse(query.verdict(), query.reason(), query.name());
        } catch (UnknownFilter e) {
            // Said above the whole view, so the lanes and the form are there to choose a filter that exists: the
            // links and the forms leave the unknown filter out, and keep the order.
            filter = GroupsFilter.NONE;
            unknownFilter = e.getMessage();
            shown = query.cleared();
        }
        if (filter.verdict().isEmpty()) {
            // Every lane shows its first groups and links to itself alone for the rest: there are no pages.
            shown = shown.page(1);
        }
        try {
            List<GroupVerdict> all = groups.groups();
            List<GroupVerdict> matching =
                    shown.order().sort(all.stream().filter(filter::matches).toList());
            Pages pages = Pages.of(shown, filter.verdict().isPresent() ? matching.size() : 0);
            return Templates.groups(
                    lanes(matching, filter, pages, shown),
                    matching.size(),
                    filter(all, filter, shown),
                    pages,
                    unknownFilter,
                    null,
                    notice,
                    kept);
        } catch (GroupsUnavailable e) {
            return Templates.groups(
                    List.of(),
                    0,
                    filter(List.of(), filter, shown),
                    Pages.of(shown, 0),
                    unknownFilter,
                    e.getMessage(),
                    notice,
                    kept);
        }
    }

    /**
     * The lanes of the groups that match the filter, in the order chosen: every lane, or that of the verdict of the
     * filter alone. Every lane shows its first {@link #PAGE_SIZE} groups; the lane of the verdict of the filter shows
     * those of its page.
     */
    static List<Lane> lanes(List<GroupVerdict> matching, GroupsFilter filter, Pages pages, Query query) {
        return LANES.stream()
                .filter(v -> filter.verdict().map(v::equals).orElse(true))
                .map(v -> {
                    List<GroupVerdict> of =
                            matching.stream().filter(g -> g.verdict() == v).toList();
                    boolean alone = filter.verdict().isPresent();
                    List<GroupVerdict> shown = alone ? pages.of(of) : of.subList(0, Math.min(PAGE_SIZE, of.size()));
                    return new Lane(
                            v.code(),
                            v.label(),
                            explanation(v),
                            of.size(),
                            shown.stream().map(GroupsPage::group).toList(),
                            alone || of.size() <= PAGE_SIZE ? "" : query.verdict(v.code()).href());
                })
                .toList();
    }

    /** What a lane holds, in one line. */
    private static String explanation(Verdict verdict) {
        return switch (verdict) {
            case KEEP -> "waiting, soonest first";
            case LEFT_ALONE -> "a person should look";
            case REMOVE -> "the next run removes these";
        };
    }

    /** The filter with the count of every reason among the groups with the verdict and the name of the filter. */
    private static Filter filter(List<GroupVerdict> all, GroupsFilter filter, Query query) {
        boolean ordered = !query.order().equals(GroupsOrder.DEFAULT);
        List<GroupVerdict> anyReason =
                all.stream().filter(filter.anyReason()::matches).toList();
        return new Filter(
                filter.verdict().map(Verdict::code).orElse(""),
                Stream.of(Reason.values())
                        .map(r -> new Option(
                                r.code(),
                                r.label(),
                                anyReason.stream().filter(g -> g.reason() == r).count(),
                                filter.reason().filter(r::equals).isPresent()))
                        .toList(),
                anyReason.size(),
                query.name(),
                filter.active(),
                sorts(query.order()),
                ordered ? query.order().column().code() : "",
                ordered ? query.order().dir() : "",
                query.page() > 1 ? String.valueOf(query.page()) : "",
                query.cleared().href(),
                filter.verdict().isPresent() ? query.verdict("").href() : "");
    }

    /** The order of the lanes, then each column the view can be sorted by; the order chosen selected. */
    static List<SortOption> sorts(GroupsOrder order) {
        boolean ordered = !order.equals(GroupsOrder.DEFAULT);
        return Stream.concat(
                        Stream.of(new SortOption("", "Lane order", !ordered)),
                        SORTS.stream()
                                .map(sort -> new SortOption(
                                        sort.getKey().code(),
                                        sort.getValue(),
                                        ordered && order.column() == sort.getKey())))
                .toList();
    }

    static Group group(GroupVerdict group) {
        String libraryLinks = group.libraryLinks() + " of " + group.files() + " files linked";
        @Nullable
        Stamp forecastDate = group.forecast()
                .filter(f -> f.until().isEmpty())
                .flatMap(Forecast::date)
                .map(StatusText::stamp)
                .orElse(null);
        String forecast = group.forecast()
                .map(f -> f.until()
                        .map(endDate -> "until " + endDate)
                        .orElseGet(() -> forecastDate == null ? "clock stopped (paused)" : forecastDate.text()))
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
                forecastDate,
                StatusText.size(group.size()),
                one ? List.of() : members,
                group.members().stream().map(GroupVerdict.Member::hash).toList(),
                group.reason() != Reason.KEEP_OVERRIDE);
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
        return slowestManaged(members)
                .map(member -> SeedProgress.of(member).slowest())
                .orElse(SeedProgress.NO_MINIMUM);
    }

    /** The managed member that meets its minimum last; empty when no member is managed. */
    static Optional<GroupVerdict.Member> slowestManaged(List<GroupVerdict.Member> members) {
        return members.stream()
                .filter(member -> member.tracker().isPresent())
                .max(Comparator.comparing((GroupVerdict.Member member) ->
                        member.tracker().orElseThrow().minimumSeedTime().minus(member.seedTime())));
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
