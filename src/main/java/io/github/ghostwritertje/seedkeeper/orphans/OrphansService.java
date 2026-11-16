package io.github.ghostwritertje.seedkeeper.orphans;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.transmission.Transmission;
import io.github.ghostwritertje.seedkeeper.transmission.TransmissionException;
import io.github.ghostwritertje.seedkeeper.transmission.TransmissionSession;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;

/** The orphans under the downloads root right now; behind the Orphans view and the API. */
@ApplicationScoped
public class OrphansService {

    private final Transmission transmission;
    private final SeedkeeperConfig config;

    OrphansService(Transmission transmission, SeedkeeperConfig config) {
        this.transmission = transmission;
        this.config = config;
    }

    /**
     * Asks Transmission for its torrents and walks the downloads root. Nothing is deleted.
     *
     * @throws OrphansUnavailable with one reason when Transmission can't be used or the downloads root can't be
     *                            read: without the torrents every file would seem an orphan
     */
    public Orphans orphans() {
        try {
            TransmissionSession session = transmission.session();
            return OrphanFinder.find(
                    config.downloadsRoot(), config.libraryRoots(), transmission.torrents(), session.incompleteDir());
        } catch (TransmissionException | OrphansException e) {
            // Each of these carries its reason as the message.
            throw new OrphansUnavailable(Objects.requireNonNull(e.getMessage()), e);
        }
    }

    /** The orphans can't be listed right now. The message is the one reason, fit to show to the user as it is. */
    public static class OrphansUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        OrphansUnavailable(String reason, Throwable cause) {
            super(reason, cause);
        }

        /** The reason; never null, as the constructor takes one. */
        @Override
        public String getMessage() {
            return Objects.requireNonNull(super.getMessage());
        }
    }
}
