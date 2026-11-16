package io.github.ghostwritertje.seedkeeper.keep;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Adds, edits and deletes keep overrides; behind the forms of the Overrides view and the Groups view. */
@ApplicationScoped
public class OverrideChanges {

    private final OverrideStore store;
    private final SeedkeeperConfig config;

    OverrideChanges(OverrideStore store, SeedkeeperConfig config) {
        this.store = store;
        this.config = config;
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
        KeepOverride added = new KeepOverride(
                UUID.randomUUID().toString(),
                cleaned,
                clean(note),
                until,
                Optional.of(OffsetDateTime.now(config.timeZone()).truncatedTo(ChronoUnit.SECONDS)));
        store.update(
                overrides -> Stream.concat(overrides.stream(), Stream.of(added)).toList());
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
            return overrides.stream()
                    .filter(override -> !override.id().equals(id))
                    .toList();
        });
    }

    /**
     * The end date of a form: empty or like 2027-03-01, as a date field sends it.
     *
     * @throws InvalidOverride when it is neither
     */
    public static Optional<LocalDate> date(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text.strip()));
        } catch (DateTimeParseException e) {
            throw new InvalidOverride("The end date is not a date like 2027-03-01: " + text.strip());
        }
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
