package io.github.ghostwritertje.seedkeeper.run;

import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.run.Deletion.Deleted;
import io.github.ghostwritertje.seedkeeper.run.RunOutcome.Removal;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import io.github.ghostwritertje.seedkeeper.transmission.Transmission;
import io.github.ghostwritertje.seedkeeper.transmission.TransmissionException;
import java.nio.file.Path;
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

    private final Transmission transmission;
    private final Deletion deletion;

    public Remover(Transmission transmission, Deletion deletion) {
        this.transmission = transmission;
        this.deletion = deletion;
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
            Set<String> gone = removed.stream().map(Torrent::hash).collect(Collectors.toSet());
            List<Torrent> remaining = transmission.torrents().stream()
                    .filter(torrent -> !gone.contains(torrent.hash()))
                    .toList();
            return new Removal(reason, torrents, removed, Optional.empty(),
                    deletion.delete(removed, remaining, incompleteDir));
        } catch (TransmissionException e) {
            // Without every member gone and a fresh torrent list there is no telling what is still in use.
            return new Removal(reason, torrents, removed, Optional.of(e.reason()), Deleted.NOTHING);
        }
    }
}
