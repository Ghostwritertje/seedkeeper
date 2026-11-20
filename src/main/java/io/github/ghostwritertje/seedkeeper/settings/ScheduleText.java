package io.github.ghostwritertje.seedkeeper.settings;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The schedule, a Quartz cron expression, in plain words. Only the common schedules have words: every day at one time,
 * and every N hours where N divides the day evenly. Any other is shown as the expression alone.
 */
final class ScheduleText {

    private static final Pattern NUMBER = Pattern.compile("\\d{1,2}");

    /** {@code *}, {@code *}{@code /N} or {@code S/N}: every N hours from hour S, or from 0. */
    private static final Pattern EVERY = Pattern.compile("(\\*|\\d{1,2})/(\\d{1,2})");

    private static final int HOURS_A_DAY = 24;

    private ScheduleText() {}

    /** "Every day at 06:00 (0 0 6 * * ?)", or the cron expression alone when it isn't a common schedule. */
    static String describe(String cron) {
        String expression = cron.strip();
        return words(expression)
                .map(words -> words + " (" + expression + ")")
                .orElse(expression);
    }

    private static Optional<String> words(String expression) {
        List<String> fields = List.of(expression.split("\\s+"));
        if (fields.size() < 6 || fields.size() > 7 || !everyDay(fields)) {
            return Optional.empty();
        }
        Optional<Integer> second = number(fields.get(0), 59);
        Optional<Integer> minute = number(fields.get(1), 59);
        if (second.isEmpty() || minute.isEmpty()) {
            return Optional.empty();
        }
        String hour = fields.get(2);
        Optional<String> daily =
                number(hour, HOURS_A_DAY - 1).map(h -> "Every day at " + time(h, minute.get(), second.get()));
        return daily.or(() -> everyHours(hour)
                .map(every -> (every.hours() == 1 ? "Every hour" : "Every " + every.hours() + " hours") + " from "
                        + time(every.from(), minute.get(), second.get())));
    }

    /** Every day of every month of every year: one day field {@code *}, the other {@code ?}. */
    private static boolean everyDay(List<String> fields) {
        String dayOfMonth = fields.get(3);
        String dayOfWeek = fields.get(5);
        boolean days = (dayOfMonth.equals("*") && dayOfWeek.equals("?"))
                || (dayOfMonth.equals("?") && dayOfWeek.equals("*"));
        boolean everyYear = fields.size() == 6 || fields.get(6).equals("*");
        return days && fields.get(4).equals("*") && everyYear;
    }

    private record Every(int from, int hours) {}

    /**
     * The hour field as every N hours from an hour: only when the runs are N hours apart all day long, over midnight
     * too.
     */
    private static Optional<Every> everyHours(String hour) {
        if (hour.equals("*")) {
            return Optional.of(new Every(0, 1));
        }
        Matcher matcher = EVERY.matcher(hour);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int from = matcher.group(1).equals("*") ? 0 : Integer.parseInt(matcher.group(1));
        int hours = Integer.parseInt(matcher.group(2));
        // Quartz refuses an increment of 24 hours or more.
        if (hours == 0 || hours >= HOURS_A_DAY || HOURS_A_DAY % hours != 0 || from >= hours) {
            return Optional.empty();
        }
        return Optional.of(new Every(from, hours));
    }

    private static Optional<Integer> number(String field, int max) {
        if (!NUMBER.matcher(field).matches()) {
            return Optional.empty();
        }
        int value = Integer.parseInt(field);
        return value <= max ? Optional.of(value) : Optional.empty();
    }

    /** "06:00", or "06:00:15" when the second isn't 0. */
    private static String time(int hour, int minute, int second) {
        return second == 0
                ? "%02d:%02d".formatted(hour, minute)
                : "%02d:%02d:%02d".formatted(hour, minute, second);
    }
}
