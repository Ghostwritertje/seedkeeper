package io.github.ghostwritertje.seedkeeper.run;

import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.run.Deletion.Deleted;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Removal;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.github.ghostwritertje.seedkeeper.transmission.Transmission;
import io.github.ghostwritertje.seedkeeper.transmission.TransmissionException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Carries out one removal (spec §4): takes a set of torrents out of Transmission, then deletes what they leave
 * behind. The set is every member of a group, or a single member that leaves its group.
 */
public final class Remover {

    /** How often the torrent list is read after the removal before giving up. */
    static final int READS = 4;

    private final Transmission transmission;
    private final Deletion deletion;
    private final Duration backoff;

    public Remover(Transmission transmission, Deletion deletion) {
        this(transmission, deletion, Duration.ofMillis(500));
    }

    /** @param backoff the wait before the second read of the torrent list; it doubles before each next one */
    Remover(Transmission transmission, Deletion deletion, Duration backoff) {
        this.transmission = transmission;
        this.deletion = deletion;
        this.backoff = backoff;
    }

    /**
     * Never throws: what goes wrong is the removal's error.
     *
     * @param torrents      the torrents that leave together
     * @param incompleteDir Transmission's incomplete dir; empty when it is not enabled
     */
    public Removal remove(Reason reason, List<Torrent> torrents, Optional<Path> incompleteDir) {
        List<Torrent> removed = new ArrayList<>();
        try {
            for (Torrent torrent : torrents) {
                // Transmission never deletes data: what goes is decided below, against the torrents that remain.
                transmission.remove(torrent.hash());
                removed.add(torrent);
            }
        } catch (TransmissionException e) {
            // Without every member gone there is no telling what is still in use; the next run finds the rest due.
            return new Removal(reason, torrents, removed, Optional.of(e.reason()), Deleted.NOTHING);
        }
        Set<String> gone = removed.stream().map(Torrent::hash).collect(Collectors.toSet());
        List<Torrent> remaining;
        try {
            remaining = torrentsAfterRemoval().stream()
                    .filter(torrent -> !gone.contains(torrent.hash()))
                    .toList();
        } catch (TransmissionException e) {
            // Never the torrent list from before the removal: a torrent added since would lose its data.
            return new Removal(reason, torrents, removed, Optional.of("Removed from Transmission, but the torrent"
                    + " list could not be read again after " + READS + " tries (" + e.reason() + "), so no file was"
                    + " deleted: what the removed torrents leave behind on disk is now an orphan"), Deleted.NOTHING);
        }
        return new Removal(reason, torrents, removed, Optional.empty(),
                deletion.delete(removed, remaining, incompleteDir));
    }

    /** Reads the torrent list, and tries again a few times, waiting longer each time, when that fails. */
    private List<Torrent> torrentsAfterRemoval() {
        Duration wait = backoff;
        for (int read = 1; ; read++) {
            try {
                return transmission.torrents();
            } catch (TransmissionException e) {
                if (read == READS) {
                    throw e;
                }
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
            wait = wait.multipliedBy(2);
        }
    }
}
