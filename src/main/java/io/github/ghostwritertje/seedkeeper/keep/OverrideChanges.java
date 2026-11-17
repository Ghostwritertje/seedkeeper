package io.github.ghostwritertje.seedkeeper.keep;

import io.github.ghostwritertje.seedkeeper.ModuleApi;
import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.keep.OverridesException.Problem;
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
import org.jspecify.annotations.Nullable;

/** Adds, edits and deletes keep overrides; behind the forms of the Overrides view and the Groups view. */
@ModuleApi
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
     * @throws OverridesException {@link Problem#INVALID} when no hash is left, {@link Problem#FILE} when the file can't
     *     be read or written
     */
    public KeepOverride add(Collection<String> hashes, String note, Optional<LocalDate> until) {
        List<String> cleaned = hashes.stream()
                .filter(hash -> hash != null && !hash.isBlank())
                .map(hash -> hash.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (cleaned.isEmpty()) {
            throw invalid("A keep override needs at least one info hash.");
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
     * @throws OverridesException {@link Problem#NO_SUCH_OVERRIDE} when the file has no override with this id,
     *     {@link Problem#FILE} when the file can't be read or written
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
     * @throws OverridesException {@link Problem#NO_SUCH_OVERRIDE} when the file has no override with this id,
     *     {@link Problem#FILE} when the file can't be read or written
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
     * @throws OverridesException {@link Problem#INVALID} when it is neither
     */
    public static Optional<LocalDate> date(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text.strip()));
        } catch (DateTimeParseException e) {
            throw invalid("The end date is not a date like 2027-03-01: " + text.strip());
        }
    }

    private static String clean(String note) {
        return note == null ? "" : note.strip();
    }

    private static void requireId(List<KeepOverride> overrides, String id) {
        if (overrides.stream().noneMatch(override -> override.id().equals(id))) {
            throw new OverridesException(
                    Problem.NO_SUCH_OVERRIDE,
                    "There is no keep override with id " + id
                            + " any more: it was deleted, or its id was changed in the file.");
        }
    }

    private static OverridesException invalid(String reason) {
        return new OverridesException(Problem.INVALID, reason);
    }
}
