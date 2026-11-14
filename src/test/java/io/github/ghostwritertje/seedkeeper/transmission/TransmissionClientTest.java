package io.github.ghostwritertje.seedkeeper.transmission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ghostwritertje.seedkeeper.transmission.FakeTransmission.Behaviour;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Arrays;
import java.util.List;
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

    private List<String> sessionIdsSent() {
        return fake.requests().stream().map(FakeTransmission.Request::sessionId).toList();
    }
}
