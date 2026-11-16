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
import io.github.ghostwritertje.seedkeeper.transmission.TransmissionSession;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private final Path downloadsRoot;
    private final String version;

    SystemService(
            Transmission transmission,
            RunService runs,
            ScheduledRun schedule,
            HistoryKeeper history,
            SeedkeeperConfig config,
            @ConfigProperty(name = "quarkus.application.version") String version) {
        this.transmission = transmission;
        this.runs = runs;
        this.schedule = schedule;
        this.history = history;
        this.timeZone = config.timeZone();
        this.downloadsRoot = config.downloadsRoot();
        this.version = version;
    }

    public SystemStatus status() {
        return new SystemStatus(
                version,
                transmission(),
                runs.running(),
                // Until the first run since the start: the last one of the history.
                runs.lastRun()
                        .map(this::lastRun)
                        .or(() -> history.lastRecordedRun().map(this::lastRun))
                        .orElse(null),
                schedule.next().map(next -> iso(next.atZone(timeZone))).orElse(null));
    }

    /** Asks Transmission now; never throws. */
    public TransmissionStatus transmission() {
        try {
            TransmissionSession session = transmission.session();
            return new TransmissionStatus(
                    true,
                    session.version(),
                    null,
                    session.incompleteDir().flatMap(this::incompleteDirWarning).orElse(null));
        } catch (TransmissionException e) {
            return new TransmissionStatus(e.reachable(), e.version(), e.reason(), null);
        }
    }

    /**
     * seedkeeper deletes in Transmission's incomplete dir as it is, wherever it is (spec §4): worth a warning when that
     * is outside the downloads root, and when it is {@code /}, which seedkeeper refuses to delete in.
     */
    private Optional<String> incompleteDirWarning(Path incompleteDir) {
        Path dir = real(incompleteDir);
        if (dir.getParent() == null) {
            return Optional.of("Transmission's incomplete dir is " + incompleteDir + ", the filesystem root:"
                    + " seedkeeper never deletes there, so the part files of removed torrents stay behind");
        }
        if (!dir.startsWith(real(downloadsRoot))) {
            return Optional.of("Transmission's incomplete dir " + incompleteDir + " is outside the downloads"
                    + " root " + downloadsRoot + ": seedkeeper deletes the part files of removed torrents there too,"
                    + " so make sure nothing else lives in it");
        }
        return Optional.empty();
    }

    /** Where the path really is: its nearest part that exists with every symlink followed, and the rest as it is. */
    private static Path real(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path existing = absolute; existing != null; existing = existing.getParent()) {
            try {
                return existing.toRealPath().resolve(existing.relativize(absolute));
            } catch (IOException e) {
                // Not there: try its parent.
            }
        }
        return absolute;
    }

    private LastRun lastRun(RunOutcome run) {
        List<String> removalErrors = new ArrayList<>();
        for (Removal removal : run.removals()) {
            String names = removal.torrents().stream().map(Torrent::name).collect(Collectors.joining(", "));
            List<Path> refused = removal.deleted().refused();
            removalErrors(
                    removalErrors,
                    names,
                    removal.error(),
                    refused.size(),
                    refused.isEmpty() ? "" : ", such as " + refused.getFirst());
        }
        return new LastRun(
                iso(run.started()),
                iso(run.ended()),
                run.status().code(),
                run.error().orElse(null),
                run.groupsRemoved(),
                run.torrentsRemoved(),
                run.filesDeleted(),
                run.spaceFreed(),
                removalErrors);
    }

    /** A run of the history: the same, but it knows only how many paths were refused, not which. */
    private LastRun lastRun(RecordedRun run) {
        List<String> removalErrors = new ArrayList<>();
        for (RecordedRemoval removal : run.removals()) {
            String names = removal.members().stream().map(RecordedMember::name).collect(Collectors.joining(", "));
            removalErrors(removalErrors, names, removal.error(), removal.pathsRefused(), "");
        }
        return new LastRun(
                iso(run.started()),
                iso(run.ended()),
                run.status(),
                run.error().orElse(null),
                run.groupsRemoved(),
                run.torrentsRemoved(),
                run.filesDeleted(),
                run.spaceFreed(),
                removalErrors);
    }

    private static void removalErrors(
            List<String> lines, String names, Optional<String> error, int refused, String example) {
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
