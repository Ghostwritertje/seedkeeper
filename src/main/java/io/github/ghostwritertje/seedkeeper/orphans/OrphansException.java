package io.github.ghostwritertje.seedkeeper.orphans;

import java.util.Objects;

/** The orphans can't be listed. The message is the one reason, fit to show to the user as it is. */
class OrphansException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    OrphansException(String reason, Throwable cause) {
        super(reason, cause);
    }

    /** The reason; never null, as the constructor takes one. */
    @Override
    public String getMessage() {
        return Objects.requireNonNull(super.getMessage());
    }
}
