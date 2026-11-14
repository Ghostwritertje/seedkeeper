package io.github.ghostwritertje.seedkeeper.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmission.Behaviour;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The Transmission client against a fake on the JDK's HttpServer; each test gets a fake and a client of its own. */
@QuarkusTest
@WithTestResource(FakeTransmissionResource.class)
class TransmissionClientTest {

    private FakeTransmission fake;
    private Transmission transmission;

    @BeforeEach
    void start() {
        fake = new FakeTransmission();
        transmission = new TransmissionClient(fake.url(), FakeTransmission.USERNAME, FakeTransmission.PASSWORD);
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    @Test
    void readsTheVersionAfterTheSessionIdHandshake() {
        TransmissionSession session = transmission.session();

        assertEquals("4.1.0 (5e6a9e0a3d)", session.version());
        assertEquals(Arrays.asList(null, "session-1"), sessionIdsSent());
        String body = fake.requests().get(1).body();
        assertTrue(body.contains("\"jsonrpc\":\"2.0\""), body);
        assertTrue(body.contains("\"method\":\"session_get\""), body);
    }

    @Test
    void keepsTheSessionIdForLaterCallsAndRenewsItWhenTransmissionRestarts() {
        transmission.session();
        transmission.session();
        fake.expireSession();
        transmission.session();

        assertEquals(Arrays.asList(null, "session-1", "session-1", "session-1", "session-2"), sessionIdsSent());
    }

    @Test
    void givesUpAfterOneRetryWhenTheSessionIdIsRefusedAgain() {
        fake.behaviour(Behaviour.ALWAYS_CONFLICT);

        TransmissionException refused = assertThrows(TransmissionException.class, transmission::session);

        assertEquals("Transmission at " + fake.url() + " keeps refusing its own session id (HTTP 409 twice in a row)",
                refused.reason());
        assertFalse(refused.reachable());
        assertEquals(2, fake.requests().size());
    }

    @Test
    void saysWhenTransmissionRejectsTheCredentials() {
        transmission = new TransmissionClient(fake.url(), FakeTransmission.USERNAME, "wrong");

        TransmissionException refused = assertThrows(TransmissionException.class, transmission::session);

        assertEquals("Transmission at " + fake.url() + " rejected the username or password (HTTP 401)",
                refused.reason());
        assertFalse(refused.reachable());
        assertEquals(1, fake.requests().size());
    }

    @Test
    void saysWhenTransmissionDoesNotAnswer() {
        fake.close();

        TransmissionException refused = assertThrows(TransmissionException.class, transmission::session);

        assertTrue(refused.reason().startsWith("Transmission at " + fake.url() + " does not answer ("),
                refused.reason());
        assertFalse(refused.reachable());
        assertNull(refused.version());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4.1.0 (5e6a9e0a3d)", "4.1.1", "4.2.0-beta.1 (0123abcd)", "5.0.0 (0123abcd)", "10.0"})
    void acceptsTransmissionFrom41On(String version) {
        fake.version(version);

        assertEquals(version, transmission.session().version());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4.0.6 (38c164933e)", "4.0.0", "3.00 (bb6b5a062e)", "2.94 (d8e60ee44f)"})
    void refusesTransmissionOlderThan41(String version) {
        fake.version(version);

        TransmissionException refused = assertThrows(TransmissionException.class, transmission::session);

        assertEquals("Transmission " + version + " is older than 4.1, the oldest version seedkeeper works with",
                refused.reason());
        assertTrue(refused.reachable());
        assertEquals(version, refused.version());
    }

    @Test
    void refusesATransmissionThatDoesNotSpeakJsonRpc2() {
        fake.behaviour(Behaviour.LEGACY);

        TransmissionException refused = assertThrows(TransmissionException.class, transmission::session);

        assertEquals("Transmission at " + fake.url() + " is older than 4.1, the oldest version seedkeeper works with"
                + " (it does not speak JSON-RPC 2.0)", refused.reason());
        assertTrue(refused.reachable());
        assertNull(refused.version());
    }

    @Test
    void passesOnAJsonRpcError() {
        fake.behaviour(Behaviour.RPC_ERROR);

        TransmissionException refused = assertThrows(TransmissionException.class, transmission::session);

        assertEquals("Transmission at " + fake.url() + " refused session_get: Method not found", refused.reason());
    }

    @Test
    void saysWhenTheAnswerIsNotFromTransmission() {
        fake.behaviour(Behaviour.GARBAGE);

        TransmissionException refused = assertThrows(TransmissionException.class, transmission::session);

        assertEquals("Transmission at " + fake.url() + " gave an answer that is not JSON-RPC; is the URL right?",
                refused.reason());
        assertFalse(refused.reachable());
    }

    @Test
    void readsTheIncompleteDirOnlyWhenItIsEnabled() {
        fake.incompleteDir("/downloads/incomplete", false);
        assertEquals(Optional.empty(), transmission.session().incompleteDir());

        fake.incompleteDir("/downloads/incomplete", true);
        assertEquals(Optional.of(Path.of("/downloads/incomplete")), transmission.session().incompleteDir());
    }

    @Test
    void readsTheTorrentsWithTheFieldsTheRulesNeed() {
        fake.torrents("""
                [{
                  "hash_string": "0123456789abcdef0123456789abcdef01234567",
                  "name": "Some.Show.S01",
                  "status": 6,
                  "percent_done": 1,
                  "error": 2,
                  "error_string": "Unregistered torrent",
                  "seconds_seeding": 1209600,
                  "is_private": true,
                  "download_dir": "/downloads/tv",
                  "trackers": [
                    {"id": 0, "tier": 0, "announce": "https://tracker-a.example/abc/announce", "scrape": "x"},
                    {"id": 1, "tier": 1, "announce": "udp://tracker-a.example:6969/announce", "scrape": "y"}
                  ],
                  "files": [
                    {"name": "Some.Show.S01/e01.mkv", "length": 1000, "bytes_completed": 1000},
                    {"name": "Some.Show.S01/sample.mkv", "length": 10, "bytes_completed": 0}
                  ],
                  "file_stats": [
                    {"wanted": true, "priority": 0, "bytes_completed": 1000},
                    {"wanted": false, "priority": 0, "bytes_completed": 0}
                  ]
                }, {
                  "hash_string": "89abcdef0123456789abcdef0123456789abcdef",
                  "name": "ubuntu.iso",
                  "status": 4,
                  "percent_done": 0.25,
                  "error": 0,
                  "error_string": "",
                  "seconds_seeding": 0,
                  "is_private": false,
                  "download_dir": "/downloads/misc",
                  "trackers": [],
                  "files": [{"name": "ubuntu.iso", "length": 4000, "bytes_completed": 1000}],
                  "file_stats": [{"wanted": true, "priority": 0, "bytes_completed": 1000}]
                }]
                """);

        List<Torrent> torrents = transmission.torrents();

        assertEquals(List.of(
                new Torrent("0123456789abcdef0123456789abcdef01234567", "Some.Show.S01", Torrent.Status.SEEDING, 1.0,
                        2, "Unregistered torrent", 1_209_600, true, "/downloads/tv",
                        List.of("https://tracker-a.example/abc/announce", "udp://tracker-a.example:6969/announce"),
                        List.of(new Torrent.File("Some.Show.S01/e01.mkv", 1000, true),
                                new Torrent.File("Some.Show.S01/sample.mkv", 10, false))),
                new Torrent("89abcdef0123456789abcdef0123456789abcdef", "ubuntu.iso", Torrent.Status.DOWNLOADING, 0.25,
                        0, "", 0, false, "/downloads/misc", List.of(),
                        List.of(new Torrent.File("ubuntu.iso", 4000, true)))),
                torrents);
        String body = fake.requests().get(1).body();
        assertTrue(body.contains("\"method\":\"torrent_get\""), body);
        for (String field : List.of("hash_string", "name", "status", "percent_done", "error", "error_string",
                "seconds_seeding", "is_private", "download_dir", "trackers", "files", "file_stats")) {
            assertTrue(body.contains("\"" + field + "\""), field + " is not asked for: " + body);
        }
    }

    @Test
    void anAnswerWithoutTorrentsIsNotFromTransmission() {
        fake.torrents("null");

        TransmissionException refused = assertThrows(TransmissionException.class, transmission::torrents);

        assertEquals("Transmission at " + fake.url() + " gave an answer that is not JSON-RPC; is the URL right?",
                refused.reason());
    }

    private List<String> sessionIdsSent() {
        return fake.requests().stream().map(FakeTransmission.Request::sessionId).toList();
    }
}
