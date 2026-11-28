package io.github.ghostwritertje.seedkeeper.system;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ghostwritertje.seedkeeper.decide.Forecast;
import io.github.ghostwritertje.seedkeeper.decide.GroupVerdict;
import io.github.ghostwritertje.seedkeeper.decide.Reason;
import io.github.ghostwritertje.seedkeeper.decide.Verdict;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Which kept groups the overview says are due soon, without starting the app. */
class DueSoonTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Brussels");

    /** Thursday 2026-11-26 12:00 in Brussels. */
    private static final Instant NOW = ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, ZONE).toInstant();

    @Test
    void theHorizonIsAWeekFromNowWhenTheNextRunIsSooner() {
        Instant tomorrow = NOW.plus(Duration.ofDays(1));

        assertEquals(NOW.plus(Duration.ofDays(7)), DueSoon.horizon(Optional.of(tomorrow), NOW));
    }

    @Test
    void theHorizonIsTheNextRunWhenThatIsMoreThanAWeekAway() {
        Instant inTenDays = NOW.plus(Duration.ofDays(10));

        assertEquals(inTenDays, DueSoon.horizon(Optional.of(inTenDays), NOW));
    }

    @Test
    void theHorizonIsAWeekFromNowWithNoRunScheduled() {
        assertEquals(NOW.plus(Duration.ofDays(7)), DueSoon.horizon(Optional.empty(), NOW));
    }

    @Test
    void keptGroupsWhoseForecastFallsByTheHorizonAreDueSoonestFirst() {
        GroupVerdict inThreeDays = kept("In.Three.Days", at(2026, 11, 29, 9));
        GroupVerdict tomorrow = kept("Tomorrow", at(2026, 11, 27, 18));
        GroupVerdict nextMonth = kept("Next.Month", at(2026, 12, 26, 9));
        GroupVerdict paused = keptWith("Paused", new Forecast(Optional.empty(), Optional.empty()));
        GroupVerdict linked = new GroupVerdict(
                List.of(member("Linked")), Verdict.KEEP, Reason.LIBRARY_LINK, 1, 1, Optional.empty());

        List<DueSoon> due = DueSoon.of(
                List.of(inThreeDays, nextMonth, paused, tomorrow, linked), NOW.plus(Duration.ofDays(7)), ZONE);

        assertEquals(
                List.of(
                        new DueSoon(tomorrow, at(2026, 11, 27, 18)),
                        new DueSoon(inThreeDays, at(2026, 11, 29, 9))),
                due);
    }

    @Test
    void aKeepOverrideIsDueAtTheEndOfItsLastDay() {
        GroupVerdict lastDayMonday =
                keptWith("Override", new Forecast(Optional.empty(), Optional.of(LocalDate.of(2026, 11, 30))));
        GroupVerdict lastDayInAMonth =
                keptWith("Later", new Forecast(Optional.empty(), Optional.of(LocalDate.of(2026, 12, 31))));

        List<DueSoon> due =
                DueSoon.of(List.of(lastDayInAMonth, lastDayMonday), NOW.plus(Duration.ofDays(7)), ZONE);

        assertEquals(List.of(new DueSoon(lastDayMonday, at(2026, 12, 1, 0))), due);
    }

    @Test
    void aForecastExactlyAtTheHorizonIsDueSoon() {
        Instant horizon = at(2026, 11, 30, 6).toInstant();
        GroupVerdict atTheHorizon = kept("At.The.Horizon", at(2026, 11, 30, 6));
        GroupVerdict aMinuteLater = kept("A.Minute.Later", at(2026, 11, 30, 6).plusMinutes(1));

        assertEquals(
                List.of(new DueSoon(atTheHorizon, at(2026, 11, 30, 6))),
                DueSoon.of(List.of(aMinuteLater, atTheHorizon), horizon, ZONE));
    }

    @Test
    void aGroupNotKeptIsNeverDueSoon() {
        GroupVerdict remove = new GroupVerdict(
                List.of(member("Remove")),
                Verdict.REMOVE,
                Reason.ALL_MET,
                0,
                1,
                Optional.of(new Forecast(Optional.of(at(2026, 11, 27, 9)), Optional.empty())));

        assertEquals(List.of(), DueSoon.of(List.of(remove), NOW.plus(Duration.ofDays(7)), ZONE));
    }

    private static GroupVerdict kept(String name, ZonedDateTime forecast) {
        return keptWith(name, new Forecast(Optional.of(forecast), Optional.empty()));
    }

    private static GroupVerdict keptWith(String name, Forecast forecast) {
        return new GroupVerdict(
                List.of(member(name)), Verdict.KEEP, Reason.MINIMUM_NOT_REACHED, 0, 1, Optional.of(forecast));
    }

    private static GroupVerdict.Member member(String name) {
        return new GroupVerdict.Member(
                name.toLowerCase(java.util.Locale.ROOT), name, Optional.empty(), Duration.ZERO, Torrent.Status.SEEDING);
    }

    private static ZonedDateTime at(int year, int month, int day, int hour) {
        return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, ZONE);
    }
}
