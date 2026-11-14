package io.github.ghostwritertje.seedkeeper.transmission;

/** What seedkeeper asks of Transmission. The rest of the application depends on this, never on the RPC client. */
public interface Transmission {

    /**
     * Asks Transmission for its session ({@code session_get}).
     *
     * @throws TransmissionException with one reason when Transmission doesn't answer or is older than 4.1
     */
    TransmissionSession session();
}
