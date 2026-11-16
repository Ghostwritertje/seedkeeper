package io.github.ghostwritertje.seedkeeper.history;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.history.History.HistoryException;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedMember;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedRemoval;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedRun;
import io.github.ghostwritertje.seedkeeper.orphans.OrphansPage;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** The Activity view: the runs of the last year and what each removed. */
@Path("/activity")
public class ActivityPage {

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @CheckedTemplate
    static class Templates {

        /**
         * @param runs        the newest first
         * @param unavailable why the history can't be read; null when it can
         */
        static native TemplateInstance activity(List<Run> runs, String unavailable);
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
            String error,
            String removed,
            int filesDeleted,
            String spaceFreed,
            List<Removal> removals) {}

    /**
     * @param members "Some.Movie (tracker-a)", with everything else worth knowing about the torrent in the brackets
     * @param reason  the label of the reason, "All met"
     * @param error   what went wrong, as a sentence; empty when nothing did
     */
    public record Removal(List<String> members, String reason, int filesDeleted, String spaceFreed, String error) {}

    private final History history;
    private final ZoneId timeZone;

    ActivityPage(History history, SeedkeeperConfig config) {
        this.history = history;
        this.timeZone = config.timeZone();
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @RunOnVirtualThread
    public TemplateInstance activity() {
        try {
            return Templates.activity(
                    history.runs(Optional.empty()).stream().map(this::run).toList(), null);
        } catch (HistoryException e) {
            return Templates.activity(List.of(), e.getMessage());
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
                OrphansPage.size(run.spaceFreed()),
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
                OrphansPage.size(removal.spaceFreed()),
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
