package io.github.ghostwritertje.seedkeeper.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ghostwritertje.seedkeeper.transmission.StatusText.Moment;
import io.github.ghostwritertje.seedkeeper.transmission.StatusText.Stamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** How a moment and how long a run took are written in the UI. */
class StatusTextTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    @Test
    void aMomentInThePastSaysHowLongAgo() {
        assertEquals(
                new Moment(
                        "2026-11-20T10:00:00+01:00",
                        "2026-11-20 10:00:00",
                        "2026-11-20 10:00:00 Europe/Brussels",
                        "3h ago"),
                StatusText.moment(Instant.parse("2026-11-20T09:00:00.700Z"), BRUSSELS, NOW));
    }

    @Test
    void aMomentInTheFutureSaysHowLongUntil() {
        assertEquals(
                new Moment(
                        "2026-11-21T04:30:00+01:00",
                        "2026-11-21 04:30:00",
                        "2026-11-21 04:30:00 Europe/Brussels",
                        "in 15h 30m"),
                StatusText.moment(Instant.parse("2026-11-21T03:30:00Z"), BRUSSELS, NOW));
    }

    @Test
    void aStampIsWrittenToTheMinuteAndKeepsTheSecondInItsDatetime() {
        assertEquals(
                new Stamp("2026-12-02T10:15:42+01:00", "2026-12-02 10:15", "2026-12-02 10:15 Europe/Brussels"),
                StatusText.stamp(Instant.parse("2026-12-02T09:15:42.500Z").atZone(BRUSSELS)));
    }

    @Test
    void aMomentLessThanAMinuteAwayIsNowOrUnderAMinute() {
        assertEquals("just now", relative(NOW));
        assertEquals("just now", relative(NOW.minusSeconds(59)));
        assertEquals("1m ago", relative(NOW.minusSeconds(60)));
        assertEquals("in under a minute", relative(NOW.plusSeconds(59)));
        assertEquals("in 1m", relative(NOW.plusSeconds(60)));
        assertEquals("2d 4h ago", relative(NOW.minus(Duration.ofHours(52))));
    }

    @Test
    void aRunTookSecondsWhenItTookLessThanAMinute() {
        assertEquals("0s", StatusText.elapsed(Duration.ZERO));
        assertEquals("4s", StatusText.elapsed(Duration.ofMillis(4_900)));
        assertEquals("59s", StatusText.elapsed(Duration.ofSeconds(59)));
        assertEquals("1m", StatusText.elapsed(Duration.ofSeconds(60)));
        assertEquals("2m 5s", StatusText.elapsed(Duration.ofSeconds(125)));
        assertEquals("1h 2m", StatusText.elapsed(Duration.ofSeconds(3_725)));
    }

    private static String relative(Instant moment) {
        return StatusText.moment(moment, BRUSSELS, NOW).relative();
    }
}
