package io.github.ghostwritertje.seedkeeper.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The schedule in plain words, with the cron expression after it; without the app. */
class ScheduleTextTest {

    @Test
    void aDailyScheduleSaysTheTimeOfDay() {
        assertEquals("Every day at 06:00 (0 0 6 * * ?)", ScheduleText.describe("0 0 6 * * ?"));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "0 30 23 ? * *       | Every day at 23:30 (0 30 23 ? * *)",
                "0 5 0 * * ? *       | Every day at 00:05 (0 5 0 * * ? *)",
                "15 0 4 * * ?        | Every day at 04:00:15 (15 0 4 * * ?)",
                "  0  0  6  *  *  ?  | Every day at 06:00 (0  0  6  *  *  ?)",
            })
    void everyWayOfWritingADailyScheduleSaysTheTimeOfDay(String cron, String text) {
        assertEquals(text, ScheduleText.describe(cron));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "0 0 */6 * * ?   | Every 6 hours from 00:00 (0 0 */6 * * ?)",
                "0 30 0/4 * * ?  | Every 4 hours from 00:30 (0 30 0/4 * * ?)",
                "0 0 3/12 ? * *  | Every 12 hours from 03:00 (0 0 3/12 ? * *)",
                "0 15 * * * ?    | Every hour from 00:15 (0 15 * * * ?)",
                "0 0 */1 * * ?   | Every hour from 00:00 (0 0 */1 * * ?)",
            })
    void anEveryNHoursScheduleSaysHowOftenAndFromWhen(String cron, String text) {
        assertEquals(text, ScheduleText.describe(cron));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0 0 6 1 1 ?", // 1 January only
                "0 0 6 ? * MON", // Mondays only
                "0 0 6 * 1 ?", // every day of January only
                "0 0 6,18 * * ?", // twice a day
                "0 0 6-8 * * ?", // a range of hours
                "0 0 6 * * ? 2027", // in one year only
                "0 0 6 * * *", // both day fields *: Quartz refuses it, so say nothing about it
                "0 0 6 ? * ?",
                "0 */5 6 * * ?", // every 5 minutes in one hour
                "* * * * * ?", // every second
                "0 0 */5 * * ?", // 20:00 to 00:00 is 4 hours, not 5
                "0 0 13/12 * * ?", // only at 13:00
                "0 0 12/12 * * ?", // only at 12:00
                "0 0 */24 * * ?",
                "0 0 */0 * * ?",
                "0 0 */x * * ?",
                "0 0 24 * * ?",
                "0 60 6 * * ?",
                "60 0 6 * * ?",
                "0 0 -6 * * ?",
                "0 0 6 * *", // too few fields
                "0 0 6 * * ? * *", // too many fields
                "",
            })
    void anyOtherScheduleIsTheCronExpressionAsItIs(String cron) {
        assertEquals(cron.strip(), ScheduleText.describe(cron));
    }
}
