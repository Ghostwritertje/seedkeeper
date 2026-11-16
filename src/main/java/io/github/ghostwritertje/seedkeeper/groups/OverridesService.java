package io.github.ghostwritertje.seedkeeper.groups;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.groups.GroupsService.GroupsUnavailable;
import io.github.ghostwritertje.seedkeeper.overrides.KeepOverride;
import io.github.ghostwritertje.seedkeeper.overrides.OverrideStore;
import io.github.ghostwritertje.seedkeeper.overrides.OverridesException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/** The keep overrides and what each does right now; behind the Overrides view and the API. */
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
    public record Listed(KeepOverride override, boolean expired, List<List<GroupVerdict.Member>> groups,
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
                .map(override -> new Listed(override, override.expired(today), current.stream()
                        .filter(members -> override.matches(hashes(members)))
                        .toList(), Optional.empty()))
                .toList();
    }

    /**
     * Adds an override at the end of the file, with a generated id and created now in the configured time zone.
     *
     * @param hashes info hashes; trimmed, lower-cased, blanks and repeats dropped
     * @return the override as it was added
     * @throws InvalidOverride when no hash is left
     * @throws OverridesException when the file can't be read or written
     */
    public KeepOverride add(Collection<String> hashes, String note, Optional<LocalDate> until) {
        List<String> cleaned = hashes.stream()
                .filter(hash -> hash != null && !hash.isBlank())
                .map(hash -> hash.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (cleaned.isEmpty()) {
            throw new InvalidOverride("A keep override needs at least one info hash.");
        }
        KeepOverride added = new KeepOverride(UUID.randomUUID().toString(), cleaned, clean(note), until,
                Optional.of(OffsetDateTime.now(config.timeZone()).truncatedTo(ChronoUnit.SECONDS)));
        store.update(overrides -> Stream.concat(overrides.stream(), Stream.of(added)).toList());
        return added;
    }

    /**
     * Changes the note and the end date of an override; its id, hashes and created stay.
     *
     * @throws NoSuchOverride when the file has no override with this id
     * @throws OverridesException when the file can't be read or written
     */
    public void edit(String id, String note, Optional<LocalDate> until) {
        store.update(overrides -> {
            requireId(overrides, id);
            return overrides.stream()
                    .map(override -> override.id().equals(id)
                            ? new KeepOverride(id, override.hashes(), clean(note), until, override.created())
                            : override)
                    .toList();
        });
    }

    /**
     * Deletes an override.
     *
     * @throws NoSuchOverride when the file has no override with this id
     * @throws OverridesException when the file can't be read or written
     */
    public void delete(String id) {
        store.update(overrides -> {
            requireId(overrides, id);
            return overrides.stream().filter(override -> !override.id().equals(id)).toList();
        });
    }

    private static String clean(String note) {
        return note == null ? "" : note.strip();
    }

    private static void requireId(List<KeepOverride> overrides, String id) {
        if (overrides.stream().noneMatch(override -> override.id().equals(id))) {
            throw new NoSuchOverride("There is no keep override with id " + id
                    + " any more: it was deleted, or its id was changed in the file.");
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

    /** What was asked for is not a keep override. The message says why, fit to show as it is. */
    public static class InvalidOverride extends RuntimeException {

        InvalidOverride(String reason) {
            super(reason);
        }
    }

    /** The override to change is not in the file. The message says so, fit to show as it is. */
    public static class NoSuchOverride extends RuntimeException {

        NoSuchOverride(String reason) {
            super(reason);
        }
    }
}
