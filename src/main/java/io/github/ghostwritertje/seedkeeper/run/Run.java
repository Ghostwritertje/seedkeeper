package io.github.ghostwritertje.seedkeeper.run;

import io.github.ghostwritertje.seedkeeper.decide.Decide;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.TrackerRules;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import io.github.ghostwritertje.seedkeeper.keep.KeepOverride;
import io.github.ghostwritertje.seedkeeper.keep.OverrideStore;
import io.github.ghostwritertje.seedkeeper.keep.OverridesException;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Removal;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Status;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot.SnapshotTorrent;
import io.github.ghostwritertje.seedkeeper.snapshot.SnapshotBuilder;
import io.github.ghostwritertje.seedkeeper.snapshot.SnapshotException;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.github.ghostwritertje.seedkeeper.transmission.Transmission;
import io.github.ghostwritertje.seedkeeper.transmission.TransmissionException;
import io.github.ghostwritertje.seedkeeper.transmission.TransmissionSession;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The steps of one run (spec §5, steps 2 and 3): ask Transmission for its session, build the snapshot, decide, and
 * carry out the removals. It holds no state and no lock; {@link RunService} sees to it that one runs at a time.
 */
final class Run {

    private final Transmission transmission;
    private final TrackerRules trackers;
    private final List<Path> libraryRoots;
    private final ZoneId timeZone;
    private final Deletion deletion;
    private final Remover remover;
    private final OverrideStore overrides;

    public Run(
            Transmission transmission,
            TrackerRules trackers,
            Path downloadsRoot,
            List<Path> libraryRoots,
            ZoneId timeZone,
            OverrideStore overrides) {
        this.overrides = overrides;
        this.transmission = transmission;
        this.trackers = trackers;
        this.libraryRoots = List.copyOf(libraryRoots);
        this.timeZone = timeZone;
        this.deletion = new Deletion(downloadsRoot, libraryRoots);
        this.remover = new Remover(transmission, deletion);
    }

    /** Never throws: a run that can't be done ends {@code failed} with the one reason. */
    public RunOutcome execute() {
        ZonedDateTime started = ZonedDateTime.now(timeZone);
        List<GroupVerdict> verdicts = List.of();
        List<Removal> removals = new ArrayList<>();
        Optional<String> error = Optional.empty();
        try {
            // Read at the start of every run (spec §7). A file that can't be read fails the run: nothing is removed.
            List<KeepOverride> keepOverrides = overrides.reload();
            TransmissionSession session = transmission.session();
            Snapshot snapshot = SnapshotBuilder.build(transmission.torrents(), session.incompleteDir(), libraryRoots);
            verdicts = Decide.decide(snapshot, trackers, keepOverrides, started);
            Map<String, Torrent> byHash = snapshot.torrents().stream()
                    .map(SnapshotTorrent::torrent)
                    .collect(Collectors.toMap(Torrent::hash, Function.identity(), (first, second) -> first));
            if (verdicts.stream().anyMatch(verdict -> verdict.verdict() == Verdict.REMOVE)) {
                // A torrent removed while its data can't be deleted leaves that data behind for good: fail first.
                deletion.checkRoots();
            }
            for (GroupVerdict verdict : verdicts) {
                if (verdict.verdict() == Verdict.REMOVE) {
                    List<Torrent> group = verdict.members().stream()
                            .map(member -> byHash.get(member.hash()))
                            .filter(Objects::nonNull)
                            .toList();
                    removals.add(remover.remove(verdict.reason(), group, session.incompleteDir()));
                }
            }
        } catch (TransmissionException | SnapshotException | OverridesException | Deletion.RootsUnavailable e) {
            error = Optional.of(e.getMessage());
        } catch (RuntimeException e) {
            error = Optional.of("The run broke off: " + e);
        }
        return new RunOutcome(
                started,
                ZonedDateTime.now(timeZone),
                error.isPresent() ? Status.FAILED : Status.SUCCEEDED,
                error,
                verdicts,
                removals);
    }
}
