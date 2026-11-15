package io.github.ghostwritertje.seedkeeper.system;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
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
    private final ZoneId timeZone;
    private final String version;

    SystemService(Transmission transmission, RunService runs, ScheduledRun schedule, SeedkeeperConfig config,
            @ConfigProperty(name = "quarkus.application.version") String version) {
        this.transmission = transmission;
        this.runs = runs;
        this.schedule = schedule;
        this.timeZone = config.timeZone();
        this.version = version;
    }

    public SystemStatus status() {
        return new SystemStatus(version, transmission(), runs.running(),
                runs.lastRun().map(SystemService::lastRun).orElse(null),
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

    private static LastRun lastRun(RunOutcome run) {
        List<String> removalErrors = new ArrayList<>();
        for (Removal removal : run.removals()) {
            String names = removal.torrents().stream().map(Torrent::name).collect(Collectors.joining(", "));
            removal.error().ifPresent(error -> removalErrors.add(names + ": " + error + ". No files were deleted."));
            List<java.nio.file.Path> refused = removal.deleted().refused();
            if (!refused.isEmpty()) {
                removalErrors.add(names + ": " + refused.size() + (refused.size() == 1 ? " path was" : " paths were")
                        + " not deleted (outside the downloads root and the incomplete dir, under a library root,"
                        + " or the filesystem refused), such as " + refused.getFirst());
            }
        }
        return new LastRun(iso(run.started()), iso(run.ended()), run.status().code(), run.error().orElse(null),
                (int) run.removals().stream().filter(removal -> removal.error().isEmpty()).count(),
                run.removals().stream().mapToInt(removal -> removal.removed().size()).sum(),
                run.removals().stream().mapToInt(removal -> removal.deleted().files().size()).sum(),
                run.spaceFreed(), removalErrors);
    }

    private static String iso(ZonedDateTime moment) {
        return moment.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
