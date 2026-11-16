package io.github.ghostwritertje.seedkeeper.keep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ghostwritertje.seedkeeper.SeedkeeperConfig;
import io.github.ghostwritertje.seedkeeper.keep.OverridesException.Problem;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adding, editing and deleting keep overrides, and the one {@link OverridesException} with its {@link Problem} that
 * tells the views which answer to give.
 */
class OverrideChangesTest {

    @TempDir
    Path dataDir;

    private Path file;
    private OverrideStore store;
    private OverrideChanges changes;

    @BeforeEach
    void changes() {
        file = dataDir.resolve(OverrideStore.FILE_NAME);
        store = new OverrideStore(file);
        changes = new OverrideChanges(store, utc());
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void addsAnOverrideWithCleanedHashes() {
        KeepOverride added = changes.add(List.of(" AAAA ", "aaaa", " ", "bbbb"), " Rewatching ", Optional.empty());

        assertEquals(List.of("aaaa", "bbbb"), added.hashes());
        assertEquals("Rewatching", added.note());
        assertEquals(List.of(added), store.overrides());
    }

    @Test
    void anOverrideWithoutHashesIsInvalid() {
        OverridesException failure = assertThrows(
                OverridesException.class, () -> changes.add(List.of(" "), "", Optional.empty()));

        assertEquals(Problem.INVALID, failure.problem());
        assertEquals("A keep override needs at least one info hash.", failure.getMessage());
    }

    @Test
    void anEndDateThatIsNoDateIsInvalid() {
        OverridesException failure = assertThrows(OverridesException.class, () -> OverrideChanges.date("March"));

        assertEquals(Problem.INVALID, failure.problem());
        assertEquals("The end date is not a date like 2027-03-01: March", failure.getMessage());
    }

    @Test
    void anEndDateIsADateOrNothing() {
        assertEquals(Optional.of(LocalDate.parse("2027-03-01")), OverrideChanges.date(" 2027-03-01 "));
        assertEquals(Optional.empty(), OverrideChanges.date(" "));
    }

    @Test
    void editingOrDeletingAnOverrideThatIsGoneFindsNone() {
        OverridesException edit = assertThrows(
                OverridesException.class, () -> changes.edit("7f3c", "note", Optional.empty()));
        OverridesException delete = assertThrows(OverridesException.class, () -> changes.delete("7f3c"));

        assertEquals(Problem.NO_SUCH_OVERRIDE, edit.problem());
        assertEquals(Problem.NO_SUCH_OVERRIDE, delete.problem());
    }

    @Test
    void editsAndDeletesAnOverride() {
        KeepOverride added = changes.add(List.of("aaaa"), "", Optional.empty());

        changes.edit(added.id(), "Rewatching", Optional.of(LocalDate.parse("2027-03-01")));
        assertEquals("Rewatching", store.overrides().getFirst().note());

        changes.delete(added.id());
        assertEquals(List.of(), store.overrides());
    }

    @Test
    void aFileThatCantBeReadIsAProblemWithTheFile() throws IOException {
        Files.writeString(file, "overrides: [unclosed");

        OverridesException failure = assertThrows(OverridesException.class, () -> changes.delete("7f3c"));

        assertEquals(Problem.FILE, failure.problem());
    }

    /** A config that answers only the time zone, the one thing OverrideChanges asks of it. */
    private static SeedkeeperConfig utc() {
        return (SeedkeeperConfig) Proxy.newProxyInstance(
                SeedkeeperConfig.class.getClassLoader(),
                new Class<?>[] {SeedkeeperConfig.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("timeZone")) {
                        return ZoneOffset.UTC;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
