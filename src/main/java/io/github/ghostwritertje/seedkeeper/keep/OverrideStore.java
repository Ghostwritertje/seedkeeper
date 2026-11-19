package io.github.ghostwritertje.seedkeeper.keep;

import io.github.ghostwritertje.seedkeeper.ModuleApi;
import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.keep.OverridesFile.Unreadable;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The keep overrides of {@code <data-dir>/overrides.yaml} (spec §7). The file is the truth: this keeps what it last
 * read from it, reads it again when asked ({@link #reload()}, at the start of every run) and when the file changes
 * ({@link #watch()}), and every change goes through {@link #update}, which writes the file in full and atomically.
 */
@ModuleApi
@ApplicationScoped
public class OverrideStore implements AutoCloseable {

    /** The name of the file in the data dir. */
    public static final String FILE_NAME = "overrides.yaml";

    private static final Logger LOG = Logger.getLogger(OverrideStore.class);

    /** What the file said when it was last read: the overrides, or why it can't be read (null when it can). */
    private record Loaded(
            List<KeepOverride> overrides, @Nullable OverridesException failure) {}

    private final Path file;
    private volatile Loaded loaded;
    /** Null until {@link #watch()} and after {@link #close()}. */
    private @Nullable WatchService watcher;

    @Inject
    OverrideStore(SeedkeeperConfig config) {
        this(config.dataDir().resolve(FILE_NAME));
    }

    /** Reads the file; one that can't be read is reported by {@link #overrides()}, not here. */
    public OverrideStore(Path file) {
        this.file = file.toAbsolutePath();
        Loaded first = read();
        OverridesException failure = first.failure();
        if (failure != null) {
            LOG.warn(failure.getMessage());
        }
        this.loaded = first;
    }

    void onStart(@Observes StartupEvent event) {
        watch();
    }

    /**
     * The overrides as the file had them when it was last read; empty when there is no file.
     *
     * @throws OverridesException when the file couldn't be read then
     */
    public List<KeepOverride> overrides() {
        Loaded current = loaded;
        OverridesException failure = current.failure();
        if (failure != null) {
            throw failure;
        }
        return current.overrides();
    }

    /**
     * Reads the file now.
     *
     * @throws OverridesException when the file can't be read
     */
    public synchronized List<KeepOverride> reload() {
        load();
        return overrides();
    }

    /**
     * Changes the overrides: reads the file now, applies the change to what it has, and when that differs writes
     * the file in full. Adding, editing and deleting an override all go through here.
     *
     * @param change from the overrides of the file to the overrides it should have
     * @return the overrides the file has now
     * @throws OverridesException when the file can't be read (it is then left as it is) or can't be written
     */
    public synchronized List<KeepOverride> update(UnaryOperator<List<KeepOverride>> change) {
        List<KeepOverride> before = reload();
        List<KeepOverride> after = List.copyOf(change.apply(before));
        if (!after.equals(before)) {
            write(OverridesFile.format(after));
            // Read back rather than trusted, so what this gives is always what the file says.
            load();
        }
        return overrides();
    }

    /**
     * A verdict group, as info hashes.
     *
     * @param members        the hashes of its members: what an override that matches it gets
     * @param crossSeedGroup the hashes of the torrents of its cross-seed group: what an override matches
     */
    public record Group(List<String> members, List<String> crossSeedGroup) {

        public Group {
            members = List.copyOf(members);
            crossSeedGroup = List.copyOf(crossSeedGroup);
        }
    }

    /**
     * Adds to every override the hashes of the members of the groups it matches (spec §5, step 4): a group that
     * gained a member since the override was set. An override matches a group when it names a torrent of the
     * group's cross-seed group. Hashes are never removed. Writes only when a hash was added.
     *
     * @param groups each current verdict group that is to have its hashes added
     */
    public List<KeepOverride> grow(Collection<Group> groups) {
        return update(overrides -> overrides.stream()
                .map(override -> override.withHashes(groups.stream()
                        .filter(group -> override.matches(group.crossSeedGroup()))
                        .flatMap(group -> group.members().stream())
                        .toList()))
                .toList());
    }

    /**
     * Starts watching the data dir, creating it when it isn't there, so that a hand edit to the file is read without
     * a restart.
     *
     * @throws OverridesException when the data dir can't be created or watched
     */
    public synchronized void watch() {
        if (watcher != null) {
            return;
        }
        // The file is an absolute path to a file, so it has a parent.
        Path dataDir = Objects.requireNonNull(file.getParent());
        WatchService service;
        try {
            Files.createDirectories(dataDir);
            service = dataDir.getFileSystem().newWatchService();
            dataDir.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watcher = service;
        } catch (IOException e) {
            throw new OverridesException(
                    "The data dir " + dataDir + " (seedkeeper.data-dir) can't be used: " + e + ". seedkeeper keeps "
                            + FILE_NAME + " there: it must be a directory seedkeeper can write to",
                    e);
        }
        // What changed between the first read and the registration would otherwise go unseen.
        load();
        Thread thread = new Thread(() -> watch(service), "overrides-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void watch(WatchService service) {
        try {
            while (true) {
                WatchKey key = service.take();
                boolean changed = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    changed |= StandardWatchEventKinds.OVERFLOW.equals(event.kind())
                            || FILE_NAME.equals(String.valueOf(event.context()));
                }
                if (changed) {
                    synchronized (this) {
                        load();
                    }
                }
                if (!key.reset()) {
                    LOG.warnf(
                            "The data dir %s is gone: changes to %s are no longer picked up until a run reads it",
                            file.getParent(), FILE_NAME);
                    return;
                }
            }
        } catch (ClosedWatchServiceException e) {
            // Closed: seedkeeper is stopping.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops watching. */
    @PreDestroy
    @Override
    public synchronized void close() {
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                LOG.debug("Closing the watcher of the data dir failed", e);
            }
            watcher = null;
        }
    }

    /** Reads the file again, and warns when it can't be read for another reason than last time. */
    private void load() {
        Loaded now = read();
        OverridesException failure = now.failure();
        OverridesException failedBefore = loaded.failure();
        if (failure != null
                && (failedBefore == null || !failedBefore.getMessage().equals(failure.getMessage()))) {
            LOG.warn(failure.getMessage());
        }
        loaded = now;
    }

    private Loaded read() {
        try {
            return new Loaded(OverridesFile.parse(Files.readString(file)), null);
        } catch (NoSuchFileException e) {
            return new Loaded(List.of(), null);
        } catch (Unreadable e) {
            return new Loaded(List.of(), unreadable(e.getMessage(), e));
        } catch (IOException e) {
            // Also a file that is not UTF-8 (MalformedInputException).
            return new Loaded(List.of(), unreadable(e.toString(), e));
        }
    }

    private OverridesException unreadable(String problem, Exception cause) {
        return new OverridesException("The overrides file " + file + " can't be read: " + problem, cause);
    }

    /** Temp file in the same dir, then one rename over the file: a reader sees the old file or the new one. */
    private void write(String content) {
        Path temp = file.resolveSibling("." + FILE_NAME + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel =
                    FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            if (Files.exists(file)) {
                try {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file));
                } catch (IOException | UnsupportedOperationException e) {
                    LOG.debugf("The permissions of %s could not be kept: %s", file, e);
                }
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Nothing more to do about the temp file.
            }
            throw new OverridesException("The overrides file " + file + " can't be written: " + e, e);
        }
    }
}
