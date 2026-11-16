package io.github.ghostwritertje.seedkeeper.overrides;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The overrides file (spec §7): read, written in full and atomically, and reloaded when it changes. */
@Timeout(120)
class OverrideStoreTest {

    private static final KeepOverride REWATCHING = new KeepOverride(
            "7f3c",
            List.of("aaaa", "bbbb"),
            "Rewatching this season",
            Optional.of(LocalDate.parse("2027-03-01")),
            Optional.of(OffsetDateTime.parse("2026-11-20T10:12:00+01:00")));
    private static final KeepOverride FOREVER =
            new KeepOverride("9d2e", List.of("cccc"), "", Optional.empty(), Optional.empty());

    @TempDir
    Path dataDir;

    private Path file;
    private OverrideStore store;

    @BeforeEach
    void store() {
        file = dataDir.resolve("overrides.yaml");
        store = new OverrideStore(file);
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void withoutAFileThereAreNoOverrides() {
        assertEquals(List.of(), store.reload());
        assertEquals(List.of(), store.overrides());
        assertFalse(Files.exists(file), "reading created the file");
    }

    @Test
    void readsTheFormatOfTheSpec() throws IOException {
        Files.writeString(file, """
                overrides:
                  - id: 7f3c                         # generated
                    hashes: [aaaa, bbbb]             # every member when it was set
                    note: "Rewatching this season"
                    until: 2027-03-01                # optional, inclusive, in time-zone
                    created: 2026-11-20T10:12:00+01:00
                  - id: 9d2e
                    hashes:
                      - cccc
                """);

        assertEquals(List.of(REWATCHING, FOREVER), store.reload());
        assertEquals(List.of(REWATCHING, FOREVER), store.overrides());
    }

    @Test
    void anInfoHashOrIdThatLooksLikeANumberIsTextAndAHashIsLowerCased() throws IOException {
        Files.writeString(file, """
                overrides:
                  - id: 0123
                    hashes: [1234567890123456789012345678901234567890, 12e4567890123456789012345678901234567890,
                             ABCDEF7890123456789012345678901234567890, 0x1f, true]
                    note: 2027
                """);

        KeepOverride override = store.reload().getFirst();

        assertEquals("0123", override.id());
        assertEquals(
                List.of(
                        "1234567890123456789012345678901234567890",
                        "12e4567890123456789012345678901234567890",
                        "abcdef7890123456789012345678901234567890",
                        "0x1f",
                        "true"),
                override.hashes());
        assertEquals("2027", override.note());
    }

    @ParameterizedTest
    @MethodSource("emptyFiles")
    void aFileWithoutOverridesIsEmpty(String content) throws IOException {
        Files.writeString(file, content);

        assertEquals(List.of(), store.reload());
    }

    static Stream<Arguments> emptyFiles() {
        return Stream.of(
                argumentSet("an empty file", ""),
                argumentSet("only a comment", "# nothing yet\n"),
                argumentSet("an empty list", "overrides: []\n"),
                argumentSet("the key without a value", "overrides:\n"));
    }

    @ParameterizedTest
    @MethodSource("brokenFiles")
    void aFileThatCannotBeReadSaysWhatIsWrongAndGivesNoOverrides(String content, String problem) throws IOException {
        Files.writeString(file, content);

        OverridesException reload = assertThrows(OverridesException.class, store::reload);
        assertTrue(
                reload.getMessage().startsWith("The overrides file " + file + " can't be read: "), reload.getMessage());
        assertTrue(reload.getMessage().contains(problem), reload.getMessage());
        // An unreadable file must never read as "no overrides": that would let a kept group be removed.
        OverridesException overrides = assertThrows(OverridesException.class, store::overrides);
        assertEquals(reload.getMessage(), overrides.getMessage());
    }

    static Stream<Arguments> brokenFiles() {
        return Stream.of(
                argumentSet("not YAML", "overrides: [\n", "YAML"),
                argumentSet("no overrides key", "keep: []\n", "'overrides'"),
                argumentSet("overrides is not a list", "overrides: yes\n", "'overrides' is not a list"),
                argumentSet("no id", "overrides:\n  - hashes: [aaaa]\n", "override 1 has no 'id'"),
                argumentSet(
                        "the same id twice",
                        "overrides:\n  - id: a\n    hashes: [aaaa]\n  - id: a\n    hashes: [bbbb]\n",
                        "override 2 has the same 'id' as an earlier one: a"),
                argumentSet("no hashes", "overrides:\n  - id: a\n", "override 1 (a) has no 'hashes'"),
                argumentSet(
                        "empty hashes", "overrides:\n  - id: a\n    hashes: []\n", "override 1 (a) has no 'hashes'"),
                argumentSet(
                        "hashes is not a list",
                        "overrides:\n  - id: a\n    hashes: aaaa\n",
                        "override 1 (a): 'hashes' is not a list"),
                argumentSet(
                        "until is not a date",
                        "overrides:\n  - id: a\n    hashes: [aaaa]\n    until: 1 March\n",
                        "override 1 (a): 'until' is not a date like 2027-03-01: 1 March"),
                argumentSet(
                        "created is not a date and time",
                        "overrides:\n  - id: a\n    hashes: [aaaa]\n    created: yesterday\n",
                        "override 1 (a): 'created' is not a date and time like 2026-11-20T10:12:00+01:00: yesterday"));
    }

    @Test
    void aFileThatIsRepairedIsReadAgain() throws IOException {
        Files.writeString(file, "overrides: [\n");
        assertThrows(OverridesException.class, store::reload);

        Files.writeString(file, "overrides:\n  - id: 9d2e\n    hashes: [cccc]\n");

        assertEquals(List.of(FOREVER), store.reload());
        assertEquals(List.of(FOREVER), store.overrides());
    }

    @Test
    void anUpdateWritesTheWholeFileInTheFormatOfTheSpec() throws IOException {
        store.update(overrides -> List.of(REWATCHING, FOREVER));

        assertEquals("""
                # Keep overrides. seedkeeper rewrites this file as a whole: comments added by hand are lost.
                overrides:
                  - id: "7f3c"
                    hashes: ["aaaa", "bbbb"]
                    note: "Rewatching this season"
                    until: 2027-03-01
                    created: 2026-11-20T10:12:00+01:00
                  - id: "9d2e"
                    hashes: ["cccc"]
                    note: ""
                """, Files.readString(file));
        assertEquals(List.of(REWATCHING, FOREVER), store.overrides());
        assertEquals(List.of(REWATCHING, FOREVER), new OverrideStore(file).overrides());
    }

    @Test
    void whatIsWrittenIsReadBackTheSameWhateverTheNoteSays() {
        KeepOverride awkward = new KeepOverride(
                "id: 1 #",
                List.of("12e4567890123456789012345678901234567890"),
                "She said \"keep\" \\ don't: remove #1\nsecond line\ttab – ünïcödé 🎬 ",
                Optional.empty(),
                Optional.of(OffsetDateTime.parse("2026-11-20T10:12:00.123456789-05:30")));

        store.update(overrides -> List.of(awkward));

        assertEquals(List.of(awkward), new OverrideStore(file).overrides());
    }

    @Test
    void anUpdateWithoutOverridesWritesAnEmptyList() throws IOException {
        store.update(overrides -> List.of(FOREVER));
        store.update(overrides -> List.of());

        assertTrue(Files.readString(file).endsWith("overrides: []\n"), Files.readString(file));
        assertEquals(List.of(), new OverrideStore(file).overrides());
    }

    @Test
    void anUpdateStartsFromTheFileAsItIsNow() throws IOException {
        store.update(overrides -> List.of(REWATCHING));
        // A hand edit the store has not seen yet.
        Files.writeString(file, "overrides:\n  - id: 9d2e\n    hashes: [cccc]\n");

        List<KeepOverride> seen = new ArrayList<>();
        store.update(overrides -> {
            seen.addAll(overrides);
            return List.of(FOREVER.withHashes(List.of("dddd")));
        });

        assertEquals(List.of(FOREVER), seen);
        assertEquals(List.of(FOREVER.withHashes(List.of("dddd"))), new OverrideStore(file).overrides());
    }

    /**
     * Atomic: the new content goes to another file in the same directory, which then takes the place of the old one
     * in one rename. So the old file is never changed: a hardlink to it still has the old content, in full.
     */
    @Test
    void anUpdateReplacesTheFileInOneRenameAndNeverChangesTheOldOne() throws IOException {
        store.update(overrides -> List.of(REWATCHING));
        String before = Files.readString(file);
        Path old = Files.createLink(dataDir.resolve("old-overrides.yaml"), file);
        Object inodeBefore = Files.getAttribute(file, "unix:ino");

        store.update(overrides -> List.of(REWATCHING, FOREVER));

        assertEquals(before, Files.readString(old), "the old file was written to");
        assertNotEquals(inodeBefore, Files.getAttribute(file, "unix:ino"), "the file was not replaced by a rename");
        assertNotEquals(before, Files.readString(file));
        assertEquals(List.of("old-overrides.yaml", "overrides.yaml"), names(dataDir), "a temp file was left behind");
    }

    @Test
    void theNewFileKeepsThePermissionsOfTheOldOne() throws IOException {
        store.update(overrides -> List.of(REWATCHING));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));

        store.update(overrides -> List.of(FOREVER));

        assertEquals("rw-rw-r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void anUpdateThatChangesNothingDoesNotWrite() throws IOException {
        store.update(overrides -> List.of(REWATCHING));
        Object inode = Files.getAttribute(file, "unix:ino");

        store.update(overrides -> List.of(REWATCHING));

        assertEquals(inode, Files.getAttribute(file, "unix:ino"));
        assertEquals(List.of(), new OverrideStore(dataDir.resolve("none.yaml")).update(overrides -> overrides));
        assertFalse(Files.exists(dataDir.resolve("none.yaml")));
    }

    @Test
    void anUpdateOfAFileThatCannotBeReadLeavesItAsItIs() throws IOException {
        Files.writeString(file, "overrides: [\n");

        assertThrows(OverridesException.class, () -> store.update(overrides -> List.of(FOREVER)));

        assertEquals("overrides: [\n", Files.readString(file));
    }

    @Test
    void anUpdateThatCannotWriteSaysSoAndLeavesNoHalfFile() throws IOException {
        store.update(overrides -> List.of(REWATCHING));
        String before = Files.readString(file);
        Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            OverridesException failure =
                    assertThrows(OverridesException.class, () -> store.update(overrides -> List.of(FOREVER)));
            assertTrue(
                    failure.getMessage().startsWith("The overrides file " + file + " can't be written: "),
                    failure.getMessage());
        } finally {
            Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertEquals(before, Files.readString(file));
        assertEquals(List.of("overrides.yaml"), names(dataDir));
        assertEquals(List.of(REWATCHING), store.overrides());
    }

    @Test
    void aHandEditIsPickedUpByTheWatcherWithoutAReload() throws Exception {
        store.update(overrides -> List.of(REWATCHING));
        store.watch();

        Files.writeString(file, "overrides:\n  - id: 9d2e\n    hashes: [cccc]\n");
        await(List.of(FOREVER));

        // Editors often save by writing another file and renaming it over the old one.
        Path saved = dataDir.resolve("overrides.yaml.swp");
        Files.writeString(saved, "overrides:\n  - id: 9d2e\n    hashes: [cccc, dddd]\n");
        Files.move(saved, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        await(List.of(FOREVER.withHashes(List.of("dddd"))));

        Files.delete(file);
        await(List.of());
    }

    @Test
    void theWatcherCreatesTheDataDirWhenItIsNotThere() throws Exception {
        Path freshDir = dataDir.resolve("not/there/yet");
        try (OverrideStore other = new OverrideStore(freshDir.resolve("overrides.yaml"))) {
            other.watch();

            assertTrue(Files.isDirectory(freshDir));
            assertEquals(List.of(), other.overrides());
        }
    }

    /** Waits until the store gives these overrides; the JDK's watcher on macOS polls, so this can take seconds. */
    private void await(List<KeepOverride> expected) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        Object last = null;
        while (System.nanoTime() < deadline) {
            try {
                last = store.overrides();
            } catch (OverridesException e) {
                last = e;
            }
            if (expected.equals(last)) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals(expected, last, "the watcher did not pick up the change");
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
