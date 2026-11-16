package io.github.ghostwritertje.seedkeeper.groups;

import io.github.ghostwritertje.seedkeeper.ModuleApi;
import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.Decide;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.TrackerRules;
import io.github.ghostwritertje.seedkeeper.keep.OverrideStore;
import io.github.ghostwritertje.seedkeeper.keep.OverridesException;
import io.github.ghostwritertje.seedkeeper.snapshot.Snapshot;
import io.github.ghostwritertje.seedkeeper.snapshot.SnapshotBuilder;
import io.github.ghostwritertje.seedkeeper.snapshot.SnapshotException;
import io.github.ghostwritertje.seedkeeper.transmission.Transmission;
import io.github.ghostwritertje.seedkeeper.transmission.TransmissionException;
import io.github.ghostwritertje.seedkeeper.transmission.TransmissionSession;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.ZonedDateTime;
import java.util.List;

/** The groups and the verdict seedkeeper would give each right now; behind the Groups view and the API. */
@ModuleApi
@ApplicationScoped
public class GroupsService {

    private final Transmission transmission;
    private final SeedkeeperConfig config;
    private final OverrideStore overrides;

    GroupsService(Transmission transmission, SeedkeeperConfig config, OverrideStore overrides) {
        this.overrides = overrides;
        this.transmission = transmission;
        this.config = config;
    }

    /**
     * Takes a fresh snapshot (Transmission and the filesystem) and decides on it. Nothing is removed.
     *
     * @throws GroupsUnavailable with one reason when Transmission can't be used, or a library root or the overrides
     *                           file can't be read
     */
    public List<GroupVerdict> groups() {
        try {
            TransmissionSession session = transmission.session();
            Snapshot snapshot =
                    SnapshotBuilder.build(transmission.torrents(), session.incompleteDir(), config.libraryRoots());
            return Decide.decide(
                    snapshot, TrackerRules.of(config), overrides.overrides(), ZonedDateTime.now(config.timeZone()));
        } catch (TransmissionException | SnapshotException | OverridesException e) {
            throw new GroupsUnavailable(e.getMessage(), e);
        }
    }

    /** There are no verdicts right now. The message is the one reason, fit to show to the user as it is. */
    @ModuleApi
    public static class GroupsUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        GroupsUnavailable(String reason, Throwable cause) {
            super(reason, cause);
        }
    }
}
