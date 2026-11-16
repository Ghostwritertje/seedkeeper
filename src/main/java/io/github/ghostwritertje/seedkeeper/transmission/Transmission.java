package io.github.ghostwritertje.seedkeeper.transmission;

import java.util.List;

/** What seedkeeper asks of Transmission. The rest of the application depends on this, never on the RPC client. */
public interface Transmission {

    /**
     * Asks Transmission for its session ({@code session_get}).
     *
     * @throws TransmissionException with one reason when Transmission doesn't answer or is older than 4.1
     */
    TransmissionSession session();

    /**
     * Asks Transmission for every torrent ({@code torrent_get}).
     *
     * @throws TransmissionException with one reason when Transmission doesn't answer
     */
    List<Torrent> torrents();

    /**
     * Removes one torrent from Transmission ({@code torrent_remove}) and leaves its data on disk
     * ({@code delete_local_data = false}): what is deleted is seedkeeper's decision, never Transmission's.
     *
     * @param hash the info hash
     * @throws TransmissionException with one reason when Transmission doesn't answer or refuses
     */
    void remove(String hash);
}
