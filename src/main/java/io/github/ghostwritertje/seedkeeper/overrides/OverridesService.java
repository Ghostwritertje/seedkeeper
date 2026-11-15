package io.github.ghostwritertje.seedkeeper.overrides;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService.GroupsUnavailable;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** The keep overrides and what each does right now; behind the Overrides view and the API. */
@ApplicationScoped
public class OverridesService {

    /**
     * One override and its state.
     *
     * @param expired true when it is past its end date in the configured time zone
     * @param groups  the members of every current group it matches; empty when it is stale
     */
    public record Listed(KeepOverride override, boolean expired, List<List<GroupVerdict.Member>> groups) {

        public Listed {
            groups = List.copyOf(groups);
        }

        /** True when none of its hashes is a torrent any more. */
        public boolean stale() {
            return groups.isEmpty();
        }

        /** True when it keeps a group: neither expired nor stale. */
        public boolean active() {
            return !expired && !stale();
        }
    }

    private final OverrideStore store;
    private final GroupsService groups;
    private final SeedkeeperConfig config;

    OverridesService(OverrideStore store, GroupsService groups, SeedkeeperConfig config) {
        this.store = store;
        this.groups = groups;
        this.config = config;
    }

    /**
     * Every override of the file, in its order, against a fresh snapshot of the groups.
     *
     * @throws OverridesUnavailable with one reason when the overrides file can't be read, or the groups can't be
     *                              known because Transmission can't be used or a library root can't be read
     */
    public List<Listed> overrides() {
        try {
            List<KeepOverride> overrides = store.overrides();
            List<List<GroupVerdict.Member>> current = groups.groups().stream().map(GroupVerdict::members).toList();
            LocalDate today = LocalDate.now(config.timeZone());
            return overrides.stream()
                    .map(override -> new Listed(override, override.expired(today), current.stream()
                            .filter(members -> override.matches(hashes(members)))
                            .toList()))
                    .toList();
        } catch (OverridesException | GroupsUnavailable e) {
            throw new OverridesUnavailable(e.getMessage(), e);
        }
    }

    private static Set<String> hashes(List<GroupVerdict.Member> members) {
        return members.stream().map(GroupVerdict.Member::hash).collect(Collectors.toSet());
    }

    /** The overrides can't be listed right now. The message is the one reason, fit to show as it is. */
    public static class OverridesUnavailable extends RuntimeException {

        OverridesUnavailable(String reason, Throwable cause) {
            super(reason, cause);
        }
    }
}
