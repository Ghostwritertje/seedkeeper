package io.github.ghostwritertje.seedkeeper.transmission;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link Transmission} in memory, for tests that need no HTTP: it holds torrents, forgets the ones that are removed
 * and can be told to fail.
 */
public final class StubTransmission implements Transmission {

    private final List<Torrent> torrents = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();
    private final Set<String> refusedRemovals = new HashSet<>();
    private Optional<Path> incompleteDir = Optional.empty();
    private TransmissionException sessionFailure;
    private Runnable onSession = () -> { };
    private int failingReadsAfterRemoval;
    private int failingReadsLeft;

    public synchronized StubTransmission add(Torrent... added) {
        torrents.addAll(List.of(added));
        return this;
    }

    public synchronized StubTransmission incompleteDir(Path dir) {
        incompleteDir = Optional.of(dir);
        return this;
    }

    /** {@code session_get} fails: Transmission doesn't answer. */
    public synchronized StubTransmission unreachable(String reason) {
        sessionFailure = TransmissionException.unreachable(reason, null);
        return this;
    }

    /** {@code session_get} fails: Transmission is older than 4.1. */
    public synchronized StubTransmission tooOld(String reason) {
        sessionFailure = TransmissionException.unsupported(reason, "4.0.6");
        return this;
    }

    /** {@code torrent_remove} of this torrent fails; the torrent stays. */
    public synchronized StubTransmission refuseRemovalOf(String hash) {
        refusedRemovals.add(hash);
        return this;
    }

    public synchronized StubTransmission allowRemovalOf(String hash) {
        refusedRemovals.remove(hash);
        return this;
    }

    /** After each {@code torrent_remove}, the next {@code times} reads of the torrent list fail. */
    public synchronized StubTransmission failReadsAfterRemoval(int times) {
        failingReadsAfterRemoval = times;
        return this;
    }

    /** Runs on every {@code session_get}, on the caller's thread, before the answer: a place to hold a run up. */
    public synchronized StubTransmission onSession(Runnable hook) {
        onSession = hook;
        return this;
    }

    /** The hashes {@code torrent_remove} took away, in order. */
    public synchronized List<String> removed() {
        return List.copyOf(removed);
    }

    public synchronized List<String> hashes() {
        return torrents.stream().map(Torrent::hash).toList();
    }

    @Override
    public TransmissionSession session() {
        Runnable hook;
        synchronized (this) {
            hook = onSession;
        }
        hook.run();
        synchronized (this) {
            if (sessionFailure != null) {
                throw sessionFailure;
            }
            return new TransmissionSession(FakeTransmission.DEFAULT_VERSION, incompleteDir);
        }
    }

    @Override
    public synchronized List<Torrent> torrents() {
        if (failingReadsLeft > 0) {
            failingReadsLeft--;
            throw TransmissionException.unreachable("Transmission does not answer torrent_get", null);
        }
        return List.copyOf(torrents);
    }

    @Override
    public synchronized void remove(String hash) {
        if (refusedRemovals.contains(hash)) {
            throw TransmissionException.unreachable("Transmission refused torrent_remove: torrent is busy", null);
        }
        torrents.removeIf(torrent -> torrent.hash().equals(hash));
        removed.add(hash);
        failingReadsLeft = failingReadsAfterRemoval;
    }
}
