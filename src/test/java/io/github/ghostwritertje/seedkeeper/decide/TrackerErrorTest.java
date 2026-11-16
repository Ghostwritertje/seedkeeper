package io.github.ghostwritertje.seedkeeper.decide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import io.github.ghostwritertje.seedkeeper.decide.TrackerError.Kind;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** What Transmission's error number and error string say about the tracker (spec §3.4), one row per pattern. */
class TrackerErrorTest {

    private static final int TRACKER_WARNING = 1;
    private static final int TRACKER_ERROR = 2;
    private static final int LOCAL_ERROR = 3;

    /** One row per built-in pattern: the pattern, and a message as a tracker sends it that only it matches. */
    static Stream<Arguments> unregisteredMessages() {
        return Stream.of(
                Arguments.of("not registered", "Torrent not registered with this tracker."),
                Arguments.of("unregistered", "Unregistered torrent"),
                Arguments.of("torrent not found", "Torrent not found"),
                Arguments.of("torrent is not found", "Torrent is not found or it is awaiting moderation"),
                Arguments.of("torrent does not exist", "Torrent does not exist"),
                Arguments.of("infohash not found", "InfoHash not found."),
                Arguments.of("unknown torrent", "Unknown torrent"),
                Arguments.of("torrent has been deleted", "Torrent has been deleted."),
                Arguments.of("torrent deleted", "Torrent deleted"),
                Arguments.of(
                        "torrent is not authorized for use on this tracker",
                        "Torrent is not authorized for use on this tracker"),
                Arguments.of("torrent banned", "Torrent banned"),
                Arguments.of("specifically banned", "This release is specifically banned"),
                Arguments.of("trumped", "Trumped: better release available"),
                Arguments.of("nuked", "Torrent has been nuked: bad audio"),
                Arguments.of("dupe", "Dupe of 12345"),
                Arguments.of("torrent existiert nicht", "Torrent existiert nicht"),
                Arguments.of("torrent nicht gefunden", "Torrent nicht gefunden"),
                Arguments.of("torrent introuvable", "Torrent introuvable"),
                Arguments.of("i'm sorry dave, i can't do that", "I'm sorry Dave, I can't do that"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unregisteredMessages")
    void aTrackerErrorThatMatchesABuiltInPatternIsUnregistered(String pattern, String message) {
        assertEquals(
                Optional.of(new TrackerError(Kind.UNREGISTERED, message)), TrackerError.of(TRACKER_ERROR, message));
    }

    @Test
    void everyBuiltInPatternHasARow() {
        List<String> rows =
                unregisteredMessages().map(row -> (String) row.get()[0]).toList();

        assertEquals(rows, TrackerError.UNREGISTERED_PATTERNS);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unregisteredMessages")
    void eachRowIsMatchedByItsOwnPatternOnly(String pattern, String message) {
        List<String> matching = TrackerError.UNREGISTERED_PATTERNS.stream()
                .filter(message.toLowerCase()::contains)
                .toList();

        assertEquals(List.of(pattern), matching);
    }

    static Stream<Arguments> otherErrors() {
        return Stream.of(
                argumentSet(
                        "a passkey problem is an account problem, not unregistered",
                        TRACKER_ERROR,
                        "Passkey does not exist!",
                        Kind.TRACKER_ERROR),
                argumentSet(
                        "passkey wins over a pattern in the same message",
                        TRACKER_ERROR,
                        "Unregistered passkey",
                        Kind.TRACKER_ERROR),
                argumentSet(
                        "a tracker error that matches no pattern",
                        TRACKER_ERROR,
                        "Connection timed out",
                        Kind.TRACKER_ERROR),
                argumentSet("a tracker error without a message", TRACKER_ERROR, "", Kind.TRACKER_ERROR),
                argumentSet(
                        "a tracker warning is never unregistered",
                        TRACKER_WARNING,
                        "Unregistered torrent",
                        Kind.TRACKER_WARNING));
    }

    @ParameterizedTest
    @MethodSource("otherErrors")
    void anyOtherTrackerErrorIsOnlyShown(int error, String message, Kind kind) {
        assertEquals(Optional.of(new TrackerError(kind, message)), TrackerError.of(error, message));
    }

    @Test
    void noErrorAndALocalErrorAreNotTrackerErrors() {
        assertEquals(Optional.empty(), TrackerError.of(0, ""));
        assertEquals(Optional.empty(), TrackerError.of(LOCAL_ERROR, "Unregistered torrent"));
    }

    @Test
    void kindsHaveKebabCaseCodes() {
        assertEquals(
                List.of("unregistered", "tracker-error", "tracker-warning"),
                Stream.of(Kind.values()).map(Kind::code).toList());
    }
}
