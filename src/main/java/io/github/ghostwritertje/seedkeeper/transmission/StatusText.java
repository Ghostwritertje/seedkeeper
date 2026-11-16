package io.github.ghostwritertje.seedkeeper.transmission;

import io.github.ghostwritertje.seedkeeper.transmission.Torrent.Status;
import java.time.Duration;

/** How a torrent's status and a seed time are written in the API and the UI. */
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
}
