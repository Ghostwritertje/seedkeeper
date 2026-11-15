package io.github.ghostwritertje.seedkeeper.snapshot;

/** The snapshot can't be built from the filesystem. The message is the one reason, fit to show to the user as it is. */
public class SnapshotException extends RuntimeException {

    SnapshotException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
