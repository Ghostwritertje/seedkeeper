package io.github.ghostwritertje.seedkeeper.decide;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What a torrent's tracker says is wrong with it (spec §3.4). Only {@link Kind#UNREGISTERED} has an effect; the
 * others are shown.
 *
 * @param message the tracker message, as Transmission reports it; may be empty
 */
public record TrackerError(Kind kind, String message) {

    /** Transmission's error numbers for what a tracker reports. */
    private static final int TRACKER_WARNING = 1;
    private static final int TRACKER_ERROR = 2;

    /** A message with this in it is about the account, not the torrent: never unregistered. */
    private static final String ACCOUNT_PROBLEM = "passkey";

    /**
     * A tracker error whose lower-cased message contains one of these says the tracker has deleted the torrent for
     * good. They come from Cleanuparr's tracker message classifier and tqm's default unregistered statuses, without
     * the ones short enough to match a passing problem ("dead", "other", "unknown", "uploaded"). Adding one takes a
     * row in {@code TrackerErrorTest}.
     */
    static final List<String> UNREGISTERED_PATTERNS = List.of(
            "not registered",
            "unregistered",
            "torrent not found",
            "torrent is not found",
            "torrent does not exist",
            "infohash not found",
            "unknown torrent",
            "torrent has been deleted",
            "torrent deleted",
            "torrent is not authorized for use on this tracker",
            "torrent banned",
            "specifically banned",
            "trumped",
            "nuked",
            "dupe",
            "torrent existiert nicht",
            "torrent nicht gefunden",
            "torrent introuvable",
            "i'm sorry dave, i can't do that");

    public enum Kind {
        /** The tracker has permanently deleted the torrent. */
        UNREGISTERED("unregistered", "Unregistered"),
        /** Any other tracker error ({@code error = 2}), such as a passkey problem or a tracker that is down. */
        TRACKER_ERROR("tracker-error", "Tracker error"),
        /** {@code error = 1}. */
        TRACKER_WARNING("tracker-warning", "Tracker warning");

        private final String code;
        private final String label;

        Kind(String code, String label) {
            this.code = code;
            this.label = label;
        }

        /** The kebab-case code the JSON API uses. */
        public String code() {
            return code;
        }

        /** As the UI shows it. */
        public String label() {
            return label;
        }
    }

    /**
     * @param error       Transmission's error number: 0 none, 1 tracker warning, 2 tracker error, 3 local error
     * @param errorString the message that goes with it
     * @return empty when the tracker reports nothing: no error, or a local error
     */
    public static Optional<TrackerError> of(int error, String errorString) {
        String message = errorString == null ? "" : errorString;
        return switch (error) {
            case TRACKER_WARNING -> Optional.of(new TrackerError(Kind.TRACKER_WARNING, message));
            case TRACKER_ERROR -> Optional.of(
                    new TrackerError(unregistered(message) ? Kind.UNREGISTERED : Kind.TRACKER_ERROR, message));
            default -> Optional.empty();
        };
    }

    public boolean unregistered() {
        return kind == Kind.UNREGISTERED;
    }

    private static boolean unregistered(String message) {
        String lowerCased = message.toLowerCase(Locale.ROOT);
        return !lowerCased.contains(ACCOUNT_PROBLEM) && UNREGISTERED_PATTERNS.stream().anyMatch(lowerCased::contains);
    }
}
