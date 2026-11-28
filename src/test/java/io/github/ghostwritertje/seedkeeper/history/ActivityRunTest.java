package io.github.ghostwritertje.seedkeeper.history;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.history.ActivityPage.Removal;
import io.github.ghostwritertje.seedkeeper.history.ActivityPage.Run;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText.Moment;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Which runs the Activity view shows on one line, and when it gives errors a column: without the app. */
class ActivityRunTest {

    @Test
    void aRunThatSucceededAndRemovedNothingIsQuiet() {
        assertTrue(run("Succeeded", null, List.of()).quiet());
    }

    @Test
    void aRunThatFailedIsNotQuiet() {
        assertFalse(run("Failed", "Transmission is unreachable", List.of()).quiet());
    }

    @Test
    void aRunThatFailedWithoutAnErrorIsNotQuiet() {
        assertFalse(run("Failed", null, List.of()).quiet());
    }

    @Test
    void aRunWithAnErrorIsNotQuiet() {
        assertFalse(run("Succeeded", "Transmission is unreachable", List.of()).quiet());
    }

    @Test
    void aRunThatRemovedSomethingIsNotQuiet() {
        assertFalse(run("Succeeded", null, List.of(removal(""))).quiet());
    }

    @Test
    void errorsGetAColumnOnlyWhenARemovalHasOne() {
        assertFalse(run("Succeeded", null, List.of(removal(""), removal(""))).removalErrors());
        assertTrue(run("Succeeded", null, List.of(removal(""), removal("Transmission refused")))
                .removalErrors());
    }

    private static Run run(String status, @Nullable String error, List<Removal> removals) {
        return new Run(
                new Moment("2026-11-20T06:00:00Z", "2026-11-20 06:00:00", "", "just now"),
                "1s",
                status,
                error,
                "",
                0,
                "0 B",
                removals);
    }

    private static Removal removal(String error) {
        return new Removal(List.of(), "All met", 1, "", "1 B", "", error);
    }
}
