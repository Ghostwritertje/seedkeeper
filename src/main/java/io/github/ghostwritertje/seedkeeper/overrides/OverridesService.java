package io.github.ghostwritertje.seedkeeper.overrides;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService.GroupsUnavailable;
import io.github.ghostwritertje.seedkeeper.keep.KeepOverride;
import io.github.ghostwritertje.seedkeeper.keep.OverrideStore;
import io.github.ghostwritertje.seedkeeper.keep.OverridesException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The keep overrides and what each does right now; behind the Overrides view and the API. Changes go through
 * {@link io.github.ghostwritertje.seedkeeper.keep.OverrideChanges}.
 */
@ApplicationScoped
public class OverridesService {

    /**
     * One override and its state.
     *
     * @param expired       true when it is past its end date in the configured time zone
     * @param groups        the members of every current group it matches; empty when it is stale, or when the groups
     *                      are unknown
     * @param groupsUnknown why the groups can't be known right now (Transmission can't be used, a library root can't
     *                      be read); then whether it is stale, and so whether it is active, is unknown too
     */
    public record Listed(
            KeepOverride override,
            boolean expired,
            List<List<GroupVerdict.Member>> groups,
            Optional<String> groupsUnknown) {

        public Listed {
            groups = List.copyOf(groups);
        }

        public boolean groupsKnown() {
            return groupsUnknown.isEmpty();
        }

        /** True when none of its hashes is a torrent any more; false also when that is unknown. */
        public boolean stale() {
            return groupsKnown() && groups.isEmpty();
        }

        /** True when it keeps a group: neither expired nor stale; false also when that is unknown. */
        public boolean active() {
            return !expired && groupsKnown() && !groups.isEmpty();
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
     * Every override of the file, in its order, against a fresh snapshot of the groups. When the groups can't be
     * known, the overrides are still listed, each with the reason.
     *
     * @throws OverridesUnavailable with one reason when the overrides file can't be read
     */
    public List<Listed> overrides() {
        List<KeepOverride> overrides;
        try {
            overrides = store.overrides();
        } catch (OverridesException e) {
            throw new OverridesUnavailable(e.getMessage(), e);
        }
        LocalDate today = LocalDate.now(config.timeZone());
        List<List<GroupVerdict.Member>> current;
        try {
            current = groups.groups().stream().map(GroupVerdict::members).toList();
        } catch (GroupsUnavailable e) {
            Optional<String> reason = Optional.of(e.getMessage());
            return overrides.stream()
                    .map(override -> new Listed(override, override.expired(today), List.of(), reason))
                    .toList();
        }
        return overrides.stream()
                .map(override -> new Listed(
                        override,
                        override.expired(today),
                        current.stream()
                                .filter(members -> override.matches(hashes(members)))
                                .toList(),
                        Optional.empty()))
                .toList();
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
