package io.github.ghostwritertje.seedkeeper.overrides;

/**
 * The overrides file can't be read or written. The message is the one reason, fit to show to the user as it is.
 * While the file can't be read there are no verdicts: an unreadable file must never count as "no overrides".
 */
public class OverridesException extends RuntimeException {

    OverridesException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
