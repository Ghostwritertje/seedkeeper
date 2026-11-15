package io.github.ghostwritertje.seedkeeper.orphans;

/** The orphans can't be listed. The message is the one reason, fit to show to the user as it is. */
public class OrphansException extends RuntimeException {

    OrphansException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
