package io.github.ghostwritertje.seedkeeper.transmission;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Transmission can't be used right now. The message is the one reason, fit to show to the user as it is. */
public class TransmissionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean reachable;
    private final @Nullable String version;

    private TransmissionException(
            String reason, boolean reachable, @Nullable String version, @Nullable Throwable cause) {
        super(reason, cause);
        this.reachable = reachable;
        this.version = version;
    }

    /** Transmission gave no usable answer. {@code cause} may be null. */
    static TransmissionException unreachable(String reason, @Nullable Throwable cause) {
        return new TransmissionException(reason, false, null, cause);
    }

    /** Transmission answered, but it is a version seedkeeper doesn't work with. {@code version} may be null. */
    static TransmissionException unsupported(String reason, @Nullable String version) {
        return new TransmissionException(reason, true, version, null);
    }

    public String reason() {
        return getMessage();
    }

    /** The reason; never null, as every factory takes one. */
    @Override
    public String getMessage() {
        return Objects.requireNonNull(super.getMessage());
    }

    /** True when Transmission answered; it is then refused because of its version. */
    public boolean reachable() {
        return reachable;
    }

    /** The version Transmission reported, or null when it didn't get that far. */
    public @Nullable String version() {
        return version;
    }
}
