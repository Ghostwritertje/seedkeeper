package io.github.ghostwritertje.seedkeeper.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.history.ActivityPage.Pages;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Which runs a page of the Activity view shows, and which pages it links to: without the app. */
class ActivityPagesTest {

    private static final List<Integer> RUNS =
            IntStream.rangeClosed(1, 25).boxed().toList();

    @Test
    void theFirstPageShowsTheNewestTwentyAndLeadsToOlderRuns() {
        Pages pages = Pages.of(null, 25);

        assertEquals(1, pages.page());
        assertEquals(2, pages.count());
        assertEquals(IntStream.rangeClosed(1, 20).boxed().toList(), pages.of(RUNS));
        assertTrue(pages.several());
        assertFalse(pages.newer());
        assertTrue(pages.older());
    }

    @Test
    void theLastPageShowsTheOldestRunsAndLeadsToNewerRuns() {
        Pages pages = Pages.of("2", 25);

        assertEquals(2, pages.page());
        assertEquals(List.of(21, 22, 23, 24, 25), pages.of(RUNS));
        assertTrue(pages.newer());
        assertFalse(pages.older());
    }

    @Test
    void aPageBelowOneIsTheFirst() {
        assertEquals(1, Pages.of("0", 25).page());
        assertEquals(1, Pages.of("-3", 25).page());
    }

    @Test
    void aPageBeyondTheLastIsTheLast() {
        assertEquals(2, Pages.of("99", 25).page());
        assertEquals(2, Pages.of("99999999999999999999", 25).page());
    }

    @Test
    void aPageThatIsNoNumberIsTheFirst() {
        assertEquals(1, Pages.of("abc", 25).page());
        assertEquals(1, Pages.of("", 25).page());
    }

    @Test
    void twentyRunsFitOnOnePage() {
        Pages pages = Pages.of(null, 20);

        assertEquals(1, pages.count());
        assertFalse(pages.several());
        assertEquals(20, pages.of(IntStream.rangeClosed(1, 20).boxed().toList()).size());
        assertTrue(Pages.of(null, 21).several());
    }

    @Test
    void noRunsIsOneEmptyPage() {
        Pages pages = Pages.of("3", 0);

        assertEquals(1, pages.page());
        assertEquals(1, pages.count());
        assertEquals(List.of(), pages.of(List.of()));
        assertFalse(pages.several());
    }
}
