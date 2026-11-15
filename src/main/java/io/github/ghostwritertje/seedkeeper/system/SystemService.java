package io.github.ghostwritertje.seedkeeper.system;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedMember;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedRemoval;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedRun;
import io.github.ghostwritertje.seedkeeper.history.HistoryKeeper;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Removal;
import io.github.ghostwritertje.seedkeeper.run.RunService;
import io.github.ghostwritertje.seedkeeper.run.ScheduledRun;
import io.github.ghostwritertje.seedkeeper.system.SystemStatus.LastRun;
import io.github.ghostwritertje.seedkeeper.system.SystemStatus.TransmissionStatus;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.github.ghostwritertje.seedkeeper.transmission.Transmission;
import io.github.ghostwritertje.seedkeeper.transmission.TransmissionException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** The state of seedkeeper and of what it depends on; behind the System view, the API and the readiness check. */
@ApplicationScoped
public class SystemService {

    private final Transmission transmission;
    private final RunService runs;
    private final ScheduledRun schedule;
    private final HistoryKeeper history;
    private final ZoneId timeZone;
    private final String version;

    SystemService(Transmission transmission, RunService runs, ScheduledRun schedule, HistoryKeeper history,
            SeedkeeperConfig config, @ConfigProperty(name = "quarkus.application.version") String version) {
        this.transmission = transmission;
        this.runs = runs;
        this.schedule = schedule;
        this.history = history;
        this.timeZone = config.timeZone();
        this.version = version;
    }

    public SystemStatus status() {
        return new SystemStatus(version, transmission(), runs.running(),
                // Until the first run since the start: the last one of the history.
                runs.lastRun().map(this::lastRun)
                        .or(() -> history.lastRecordedRun().map(this::lastRun))
                        .orElse(null),
                schedule.next().map(next -> iso(next.atZone(timeZone))).orElse(null));
    }

    /** Asks Transmission now; never throws. */
    public TransmissionStatus transmission() {
        try {
            return new TransmissionStatus(true, transmission.session().version(), null);
        } catch (TransmissionException e) {
            return new TransmissionStatus(e.reachable(), e.version(), e.reason());
        }
    }

    private LastRun lastRun(RunOutcome run) {
        List<String> removalErrors = new ArrayList<>();
        for (Removal removal : run.removals()) {
            String names = removal.torrents().stream().map(Torrent::name).collect(Collectors.joining(", "));
            List<java.nio.file.Path> refused = removal.deleted().refused();
            removalErrors(removalErrors, names, removal.error(), refused.size(),
                    refused.isEmpty() ? "" : ", such as " + refused.getFirst());
        }
        return new LastRun(iso(run.started()), iso(run.ended()), run.status().code(), run.error().orElse(null),
                run.groupsRemoved(), run.torrentsRemoved(), run.filesDeleted(), run.spaceFreed(), removalErrors);
    }

    /** A run of the history: the same, but it knows only how many paths were refused, not which. */
    private LastRun lastRun(RecordedRun run) {
        List<String> removalErrors = new ArrayList<>();
        for (RecordedRemoval removal : run.removals()) {
            String names = removal.members().stream().map(RecordedMember::name).collect(Collectors.joining(", "));
            removalErrors(removalErrors, names, removal.error(), removal.pathsRefused(), "");
        }
        return new LastRun(iso(run.started()), iso(run.ended()), run.status(), run.error().orElse(null),
                run.groupsRemoved(), run.torrentsRemoved(), run.filesDeleted(), run.spaceFreed(), removalErrors);
    }

    private static void removalErrors(List<String> lines, String names, java.util.Optional<String> error,
            int refused, String example) {
        error.ifPresent(reason -> lines.add(names + ": " + reason + ". No files were deleted."));
        if (refused > 0) {
            lines.add(names + ": " + refused + (refused == 1 ? " path was" : " paths were")
                    + " not deleted (outside the downloads root and the incomplete dir, under a library root,"
                    + " while a root could not be found, or the filesystem refused)" + example);
        }
    }

    private String iso(Instant moment) {
        return iso(moment.atZone(timeZone));
    }

    private static String iso(ZonedDateTime moment) {
        return moment.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
