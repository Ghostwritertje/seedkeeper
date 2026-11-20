package io.github.ghostwritertje.seedkeeper.history;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ghostwritertje.seedkeeper.history.ActivityPage.Removal;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedMember;
import io.github.ghostwritertje.seedkeeper.history.History.RecordedRemoval;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** How the Activity view explains a removal that deleted no files or freed no space: without the app. */
class ActivityRemovalTest {

    private static final String NOTHING_DELETED = "Data kept: every file is still used by a torrent that stays"
            + " (a cross-seed), or was no longer on disk.";
    private static final String NOTHING_FREED = "Data kept: what was deleted was a symlink, or a file still linked"
            + " elsewhere, such as in the library.";

    @Test
    void aRemovalThatDeletedAndFreedSomethingHasNoNote() {
        Removal removal = ActivityPage.removal(removal(Optional.empty(), 2, 1024, 0));

        assertEquals(2, removal.filesDeleted());
        assertEquals("1.0 KiB", removal.spaceFreed());
        assertEquals("", removal.filesNote());
        assertEquals("", removal.spaceNote());
    }

    @Test
    void aRemovalThatDeletedNoFilesSaysTheDataWasKept() {
        Removal removal = ActivityPage.removal(removal(Optional.empty(), 0, 0, 0));

        assertEquals(NOTHING_DELETED, removal.filesNote());
        assertEquals("", removal.spaceNote());
        assertEquals("", removal.error());
    }

    @Test
    void aRemovalThatDeletedFilesButFreedNoSpaceSaysTheDataIsLinkedElsewhere() {
        Removal removal = ActivityPage.removal(removal(Optional.empty(), 1, 0, 0));

        assertEquals("", removal.filesNote());
        assertEquals(NOTHING_FREED, removal.spaceNote());
    }

    @Test
    void aRemovalThatFailedIsExplainedByItsErrorAlone() {
        Removal removal = ActivityPage.removal(removal(Optional.of("Transmission refused"), 0, 0, 0));

        assertEquals("", removal.filesNote());
        assertEquals("", removal.spaceNote());
        assertEquals("Transmission refused. No files were deleted.", removal.error());
    }

    @Test
    void aRemovalWithRefusedPathsIsExplainedByThemAlone() {
        Removal removal = ActivityPage.removal(removal(Optional.empty(), 0, 0, 1));

        assertEquals("", removal.filesNote());
        assertEquals("", removal.spaceNote());
        assertEquals(
                "1 path was not deleted (outside the downloads root and the incomplete dir, under a library root, or"
                        + " the filesystem refused).",
                removal.error());
    }

    private static RecordedRemoval removal(Optional<String> error, int filesDeleted, long spaceFreed, int refused) {
        return new RecordedRemoval(
                1,
                Instant.EPOCH,
                "all-met",
                error,
                spaceFreed,
                filesDeleted,
                refused,
                List.of(new RecordedMember(
                        "hash", "Some.Movie", Optional.of("tracker-a"), Optional.empty(), false, true)));
    }
}
