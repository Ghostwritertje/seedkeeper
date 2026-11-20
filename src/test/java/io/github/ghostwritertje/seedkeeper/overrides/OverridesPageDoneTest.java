package io.github.ghostwritertje.seedkeeper.overrides;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ghostwritertje.seedkeeper.overrides.OverridesPage.Done;
import org.junit.jupiter.api.Test;

/** What the Overrides view says after a change, for each value of {@code done} in its address. */
class OverridesPageDoneTest {

    @Test
    void eachChangeHasItsOwnText() {
        assertEquals("Keep override added.", Done.text("added"));
        assertEquals("Keep override saved.", Done.text("saved"));
        assertEquals("Keep override deleted.", Done.text("deleted"));
    }

    @Test
    void anyOtherValueSaysNothing() {
        assertNull(Done.text(null));
        assertNull(Done.text(""));
        assertNull(Done.text("ADDED"));
        assertNull(Done.text("kept"));
        assertNull(Done.text("<script>"));
    }
}
