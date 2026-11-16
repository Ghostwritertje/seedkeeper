package io.github.ghostwritertje.seedkeeper.transmission;

/** Transmission can't be used right now. The message is the one reason, fit to show to the user as it is. */
public class TransmissionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean reachable;
    private final String version;

    private TransmissionException(String reason, boolean reachable, String version, Throwable cause) {
        super(reason, cause);
        this.reachable = reachable;
        this.version = version;
    }

    /** Transmission gave no usable answer. */
    static TransmissionException unreachable(String reason, Throwable cause) {
        return new TransmissionException(reason, false, null, cause);
    }

    /** Transmission answered, but it is a version seedkeeper doesn't work with. {@code version} may be null. */
    static TransmissionException unsupported(String reason, String version) {
        return new TransmissionException(reason, true, version, null);
    }

    public String reason() {
        return getMessage();
    }

    /** True when Transmission answered; it is then refused because of its version. */
    public boolean reachable() {
        return reachable;
    }

    /** The version Transmission reported, or null when it didn't get that far. */
    public String version() {
        return version;
    }
}
