package io.github.ghostwritertje.seedkeeper.history;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.history.History.HistoryException;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedMember;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedRemoval;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedRun;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** The Activity view: the runs of the last year and what each removed, 20 runs to a page. */
@Path("/activity")
class ActivityPage {

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @CheckedTemplate
    static class Templates {

        /**
         * @param runs        the runs of this page, the newest first
         * @param pages       which page this is, out of how many
         * @param unavailable why the history can't be read; null when it can
         */
        static native TemplateInstance activity(List<Run> runs, Pages pages, @Nullable String unavailable);
    }

    /**
     * One run, as text ready to show.
     *
     * @param started    "2026-11-20 06:00:00", in the configured time zone
     * @param status     "Succeeded" or "Failed"
     * @param error      the one reason of a failed run; null otherwise
     * @param removed    "2 torrents in 1 group"
     * @param spaceFreed "1.5 GiB"
     */
    public record Run(
            String started,
            String ended,
            String status,
            @Nullable String error,
            String removed,
            int filesDeleted,
            String spaceFreed,
            List<Removal> removals) {}

    /**
     * One removal of the run, as text ready to show.
     *
     * @param members "Some.Movie (tracker-a)", with everything else worth knowing about the torrent in the brackets
     * @param reason  the label of the reason, "All met"
     * @param error   what went wrong, as a sentence; empty when nothing did
     */
    public record Removal(List<String> members, String reason, int filesDeleted, String spaceFreed, String error) {}

    /**
     * The page of runs the Activity view shows, and how many pages there are.
     *
     * @param page  1-based, between 1 and {@code count}
     * @param count 1 when there are no runs
     */
    public record Pages(int page, int count) {

        /**
         * @param requested the {@code page} query parameter; a page below 1 or one that is no number is the first, a
         *                  page beyond the last is the last
         * @param runs      how many runs there are
         */
        static Pages of(@Nullable String requested, int runs) {
            int count = Math.max(1, (runs + PAGE_SIZE - 1) / PAGE_SIZE);
            if (requested == null || !requested.strip().matches("[+-]?\\d+")) {
                return new Pages(1, count);
            }
            BigInteger page = new BigInteger(requested.strip());
            return new Pages(
                    page.max(BigInteger.ONE).min(BigInteger.valueOf(count)).intValueExact(), count);
        }

        /** The runs of this page, out of all of them, the newest first. */
        <T> List<T> of(List<T> runs) {
            int from = (page - 1) * PAGE_SIZE;
            return runs.subList(Math.min(from, runs.size()), Math.min(from + PAGE_SIZE, runs.size()));
        }

        /** Whether there is more than one page, so the view links between them. */
        public boolean several() {
            return count > 1;
        }

        /** Whether there is a page of newer runs before this one. */
        public boolean newer() {
            return page > 1;
        }

        /** Whether there is a page of older runs after this one. */
        public boolean older() {
            return page < count;
        }
    }

    /** How many runs one page of the Activity view shows at most. */
    static final int PAGE_SIZE = 20;

    private final History history;
    private final ZoneId timeZone;

    ActivityPage(History history, SeedkeeperConfig config) {
        this.history = history;
        this.timeZone = config.timeZone();
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public TemplateInstance activity(@QueryParam("page") @Nullable String page) {
        try {
            List<RecordedRun> runs = history.runs(Optional.empty());
            Pages pages = Pages.of(page, runs.size());
            return Templates.activity(pages.of(runs).stream().map(this::run).toList(), pages, null);
        } catch (HistoryException e) {
            return Templates.activity(List.of(), Pages.of(null, 0), e.getMessage());
        }
    }

    private Run run(RecordedRun run) {
        return new Run(
                moment(run.started()),
                moment(run.ended()),
                run.status().equals("succeeded") ? "Succeeded" : "Failed",
                run.error().orElse(null),
                count(run.torrentsRemoved(), "torrent") + " in " + count(run.groupsRemoved(), "group"),
                run.filesDeleted(),
                StatusText.size(run.spaceFreed()),
                run.removals().stream().map(ActivityPage::removal).toList());
    }

    private static Removal removal(RecordedRemoval removal) {
        List<String> errors = new ArrayList<>();
        removal.error().ifPresent(error -> errors.add(error + ". No files were deleted."));
        if (removal.pathsRefused() > 0) {
            errors.add(removal.pathsRefused() + (removal.pathsRefused() == 1 ? " path was" : " paths were")
                    + " not deleted (outside the downloads root and the incomplete dir, under a library root, or"
                    + " the filesystem refused).");
        }
        return new Removal(
                removal.members().stream().map(ActivityPage::member).toList(),
                reason(removal.reason()),
                removal.filesDeleted(),
                StatusText.size(removal.spaceFreed()),
                String.join(" ", errors));
    }

    /** "Some.Movie (tracker-a, not finished, tracker said: unregistered torrent, not removed)". */
    private static String member(RecordedMember member) {
        List<String> notes = new ArrayList<>();
        notes.add(member.tracker().orElse("unmanaged"));
        if (member.unfinished()) {
            notes.add("not finished");
        }
        member.trackerMessage().ifPresent(message -> notes.add("tracker said: " + message));
        if (!member.removed()) {
            notes.add("not removed");
        }
        return member.name() + " (" + String.join(", ", notes) + ")";
    }

    /** The label of a reason; the code itself when another version recorded a reason this one doesn't know. */
    private static String reason(String code) {
        return Stream.of(Reason.values())
                .filter(reason -> reason.code().equals(code))
                .map(Reason::label)
                .findFirst()
                .orElse(code);
    }

    private String moment(Instant moment) {
        return MOMENT.format(moment.atZone(timeZone));
    }

    private static String count(int number, String what) {
        return number + " " + what + (number == 1 ? "" : "s");
    }
}
