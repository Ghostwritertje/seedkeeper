package io.github.ghostwritertje.seedkeeper.transmission;

import io.github.ghostwritertje.seedkeeper.ModuleApi;
import io.github.ghostwritertje.seedkeeper.transmission.Torrent.Status;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** How a torrent's status, a seed time, a size and a moment are written in the API and the UI. */
@ModuleApi
public final class StatusText {

    private StatusText() {}

    /** The kebab-case code the JSON API uses. */
    public static String code(Status status) {
        return switch (status) {
            case STOPPED -> "stopped";
            case CHECK_WAIT -> "check-wait";
            case CHECKING -> "checking";
            case DOWNLOAD_WAIT -> "download-wait";
            case DOWNLOADING -> "downloading";
            case SEED_WAIT -> "seed-wait";
            case SEEDING -> "seeding";
        };
    }

    /** As the UI shows it. */
    public static String label(Status status) {
        return switch (status) {
            case STOPPED -> "Stopped";
            case CHECK_WAIT -> "Queued to verify";
            case CHECKING -> "Verifying";
            case DOWNLOAD_WAIT -> "Queued to download";
            case DOWNLOADING -> "Downloading";
            case SEED_WAIT -> "Queued to seed";
            case SEEDING -> "Seeding";
        };
    }

    /** A duration in its two largest units, rounded down: "40d", "2d 12h", "5h 3m", "0m". */
    public static String duration(Duration duration) {
        long days = duration.toDays();
        int hours = duration.toHoursPart();
        int minutes = duration.toMinutesPart();
        if (days > 0) {
            return days + "d" + (hours > 0 ? " " + hours + "h" : "");
        }
        if (hours > 0) {
            return hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        }
        return minutes + "m";
    }

    /**
     * A moment as the UI shows it, in a {@code <time>} element.
     *
     * @param datetime ISO 8601 to the second, with the offset of the time zone: "2026-11-20T10:00:00+01:00", for the
     *                 element's {@code datetime}
     * @param text     "2026-11-20 10:00:00", in the time zone
     * @param title    the text with the time zone, for the tooltip: "2026-11-20 10:00:00 Europe/Brussels"
     * @param relative how long ago or until when, from now: "3h ago", "in 15h 30m", "just now"
     */
    public record Moment(String datetime, String text, String title, String relative) {}

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** A moment in a time zone, and how far it is from now. */
    public static Moment moment(Instant moment, ZoneId zone, Instant now) {
        ZonedDateTime zoned = moment.atZone(zone).truncatedTo(ChronoUnit.SECONDS);
        String text = zoned.format(MOMENT);
        return new Moment(
                zoned.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                text,
                text + " " + zone.getId(),
                relative(Duration.between(now.truncatedTo(ChronoUnit.SECONDS), zoned.toInstant())));
    }

    /**
     * A moment written to the minute, in a {@code <time>} element, with no relative time: for a moment the row around
     * it already puts in context.
     *
     * @param datetime ISO 8601 to the second, with the offset: "2026-11-20T10:12:00+01:00"
     * @param text     "2026-11-20 10:12"
     * @param title    the text with the time zone, for the tooltip: "2026-11-20 10:12 Europe/Brussels"
     */
    public record Stamp(String datetime, String text, String title) {}

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** A moment in the time zone it is in, to the minute. */
    public static Stamp stamp(ZonedDateTime moment) {
        ZonedDateTime second = moment.truncatedTo(ChronoUnit.SECONDS);
        String text = second.format(MINUTE);
        return new Stamp(
                second.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                text,
                text + " " + moment.getZone().getId());
    }

    private static String relative(Duration untilThen) {
        if (untilThen.abs().compareTo(Duration.ofMinutes(1)) < 0) {
            return untilThen.isPositive() ? "in under a minute" : "just now";
        }
        return untilThen.isNegative() ? duration(untilThen.negated()) + " ago" : "in " + duration(untilThen);
    }

    /** How long a run took, in seconds while it was under an hour: "4s", "2m 5s", "1m", "1h 2m". */
    public static String elapsed(Duration elapsed) {
        if (elapsed.compareTo(Duration.ofHours(1)) >= 0) {
            return duration(elapsed);
        }
        long minutes = elapsed.toMinutes();
        int seconds = elapsed.toSecondsPart();
        if (minutes == 0) {
            return seconds + "s";
        }
        return minutes + "m" + (seconds > 0 ? " " + seconds + "s" : "");
    }

    /** Bytes in the largest binary unit that keeps the number at 1 or more: "512 B", "1.5 KiB", "3.2 GiB". */
    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
